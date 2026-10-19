package dev.sash.app.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.sash.protocol.HevcSps
import dev.sash.protocol.ParameterSets
import java.nio.ByteBuffer

/**
 * The Mac's HEVC stream into a Surface. The host sends VPS/SPS/PPS (kind 0) before every keyframe
 * and each frame (kind 1) as one access unit whose NALs carry 4-byte big-endian lengths; MediaCodec
 * wants Annex-B start codes, so the lengths are rewritten in place. Frames render as soon as they
 * decode (the Mac paces; nothing here waits on timestamps). Until a keyframe after new parameter
 * sets, or after any decoder error, frames are dropped.
 */
class VideoDecoder {
    private val thread = HandlerThread("hevc").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var surface: Surface? = null
    val hasSurface: Boolean get() = surface != null
    private var codec: MediaCodec? = null
    private var params: List<ByteArray>? = null
    private var nalLength = 4
    private var waitingForKeyframe = true
    private var pts = 0L

    /** The frame size the SPS gives, once decoding: the UI letterboxes to it. */
    @Volatile var onVideoSize: ((Int, Int) -> Unit)? = null
    /** Each frame decoded and shown. */
    @Volatile var onFrameShown: (() -> Unit)? = null
    /**
     * A frame had to be dropped, so nothing decodes until a keyframe. The device cannot ask the
     * Mac for one directly; the owner restarts the stream, which begins with one.
     */
    @Volatile var onStall: (() -> Unit)? = null
    /**
     * A new surface (the app back from the background) shows nothing until a keyframe, and a Mac
     * whose picture stands still sends none: the owner asks for one.
     */
    @Volatile var onNewSurface: (() -> Unit)? = null

    fun attach(s: Surface?) = handler.post {
        if (s === surface) return@post
        release()
        surface = s
        waitingForKeyframe = true
        if (s != null) onNewSurface?.invoke()
    }

    fun parameterSets(ps: ParameterSets) = handler.post {
        val same = params?.let { old -> old.size == ps.sets.size && old.indices.all { old[it].contentEquals(ps.sets[it]) } } ?: false
        nalLength = ps.nalUnitHeaderLength
        if (same && codec != null) return@post
        params = ps.sets
        release()
        waitingForKeyframe = true
    }

    fun frame(accessUnit: ByteArray, keyframe: Boolean) = handler.post {
        if (waitingForKeyframe && !keyframe) return@post
        val s = surface ?: return@post
        val ps = params ?: return@post
        try {
            if (codec == null) configure(s, ps)
            val c = codec ?: return@post
            val annexB = toAnnexB(accessUnit, nalLength) ?: return@post
            // A busy decoder frees an input buffer once it hands frames out: keep draining while
            // waiting (this thread is the decoder's own). Only a decoder stuck for half a second
            // loses the frame, and then the stream has to start again from a keyframe.
            var index = -1
            val deadline = System.nanoTime() + 500_000_000L
            while (index < 0 && System.nanoTime() < deadline) {
                index = c.dequeueInputBuffer(10_000)
                if (index < 0) drain(c)
            }
            if (index < 0) {
                stalled()
                return@post
            }
            val buf = c.getInputBuffer(index)!!
            buf.clear()
            if (annexB.size > buf.capacity()) {
                c.queueInputBuffer(index, 0, 0, pts, 0)
                stalled()
                return@post
            }
            buf.put(annexB)
            pts += 16_666
            c.queueInputBuffer(index, 0, annexB.size, pts, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            waitingForKeyframe = false
            drain(c)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed; waiting for the next keyframe", e)
            release()
            stalled()
        }
    }

    private fun stalled() {
        waitingForKeyframe = true
        onStall?.invoke()
    }

    private fun configure(s: Surface, ps: List<ByteArray>) {
        val csd = ByteArray(0).let { acc -> ps.fold(acc) { a, set -> a + START + set } }
        val (w, h) = ps.firstNotNullOfOrNull { HevcSps.size(it) } ?: (1920 to 1080)
        onVideoSize?.invoke(w, h)
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
        // Ask for the decoder's low-latency mode where it has one (Android 11+).
        f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        c.configure(f, s, null, 0)
        c.start()
        codec = c
        handler.removeCallbacks(pump)
        handler.post(pump)
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    c.releaseOutputBuffer(out, true)
                    onFrameShown?.invoke()
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    onVideoSize?.invoke(w, h)
                }
                else -> return
            }
        }
    }

    /** Poll for output while a decoder runs: some decoders return frames late. Idle otherwise. */
    private val pump = object : Runnable {
        override fun run() {
            val c = codec ?: return
            try { drain(c) } catch (_: Exception) {}
            handler.postDelayed(this, 4)
        }
    }

    private fun release() {
        handler.removeCallbacks(pump)
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
    }

    fun shutdown() = handler.post {
        release()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private val START = byteArrayOf(0, 0, 0, 1)

        /** Length-prefixed NALs (hvcC style) to Annex-B. Null if a length runs past the end. */
        fun toAnnexB(au: ByteArray, nalLength: Int): ByteArray? {
            if (nalLength == 4) {
                // Same size: rewrite each 4-byte length as a start code in a copy.
                val out = au.copyOf()
                var off = 0
                while (off + 4 <= out.size) {
                    val len = ((out[off].toInt() and 0xFF) shl 24) or ((out[off + 1].toInt() and 0xFF) shl 16) or
                        ((out[off + 2].toInt() and 0xFF) shl 8) or (out[off + 3].toInt() and 0xFF)
                    if (len < 0 || off + 4 + len > out.size) return null
                    out[off] = 0; out[off + 1] = 0; out[off + 2] = 0; out[off + 3] = 1
                    off += 4 + len
                }
                return if (off == out.size) out else null
            }
            val parts = ArrayList<ByteArray>()
            var off = 0
            while (off + nalLength <= au.size) {
                var len = 0
                for (i in 0 until nalLength) len = (len shl 8) or (au[off + i].toInt() and 0xFF)
                if (off + nalLength + len > au.size) return null
                parts += START + au.copyOfRange(off + nalLength, off + nalLength + len)
                off += nalLength + len
            }
            return parts.fold(ByteArray(0)) { a, b -> a + b }
        }
    }
}
