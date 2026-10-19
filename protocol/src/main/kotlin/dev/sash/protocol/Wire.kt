package dev.sash.protocol

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The Sill for iPhone and iPad release whose protocol this client matches, sent as the hello's
 * `appVersion`. A host judges devices by it (SillHost/DeviceGate.swift: a floor it raises for a
 * requirement older devices cannot meet), so it names what this client really speaks, never more:
 * raise it only once the protocol of that Sill release is matched.
 */
object SillParity {
    const val VERSION = "0.5.1"
}

/**
 * Sill's wire format (reference/sill/Sources/StreamProtocol/StreamMessage.swift).
 * Header, 14 bytes, big endian: kind(1) timestamp(8, IEEE double) isKeyframe(1) payloadLength(4).
 */
object Kind {
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    const val WINDOW_LIST = 2
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    const val SELECT_SOURCE = 6
    const val LAUNCH_APP = 7
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val CLIENT_STATS = 12
    const val TICK = 13
    const val CURSOR_SHAPE = 14
    const val WINDOW_COMMAND = 15
    const val HOST_SETTINGS = 16
    const val CHANGE_SETTINGS = 17
    const val MAC_INFO = 18
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val PAIRING_WANTED = 21
    const val GOODBYE = 22
    const val HELLO = 23
    const val MAC_MENU = 24
    const val PRESS_MENU_ITEM = 25
    const val MAC_POINTER = 26
    const val FETCH_MENU = 27
    const val GESTURE = 28
    const val CLIPBOARD = 32
}

class Message(
    val kind: Int,
    val timestamp: Double,
    val isKeyframe: Boolean,
    val payload: ByteArray,
) {
    fun serialized(): ByteArray {
        val b = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        b.put(kind.toByte())
        b.putDouble(timestamp)
        b.put(if (isKeyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    companion object {
        const val HEADER_LENGTH = 14

        /** Largest client → host payload a host accepts (1 MiB). */
        const val MAX_CLIENT_PAYLOAD = 1 shl 20
        const val MAX_PAIRING_PAYLOAD = 4096
        /** A video frame as a device accepts it. */
        const val MAX_FRAME_PAYLOAD = 32 shl 20
        /** Anything else the host sends. */
        const val MAX_OTHER_HOST_PAYLOAD = 4 shl 20

        fun now(): Double = System.currentTimeMillis() / 1000.0

        fun of(kind: Int, payload: ByteArray = ByteArray(0)) = Message(kind, now(), false, payload)
    }
}

/** Reads whole messages; refuses lengths a host could never legitimately send. */
class MessageReader(private val input: DataInputStream) {
    private val header = ByteArray(Message.HEADER_LENGTH)

    /** Called with every chunk of bytes read, so liveness can count partial payloads. */
    var onBytes: (() -> Unit)? = null

    /** Null on a clean end of stream between messages. */
    fun read(): Message? {
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            return null
        }
        onBytes?.invoke()
        val b = ByteBuffer.wrap(header)
        val kind = b.get().toInt() and 0xFF
        val ts = b.double
        val key = b.get().toInt() == 1
        val len = b.int.toLong() and 0xFFFFFFFFL
        val cap = if (kind == Kind.FRAME) Message.MAX_FRAME_PAYLOAD else Message.MAX_OTHER_HOST_PAYLOAD
        if (len > cap) throw IOException("the host announced $len bytes for kind $kind")
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val n = input.read(payload, off, minOf(256 * 1024, payload.size - off))
            if (n < 0) throw EOFException("stream ended inside a message")
            off += n
            onBytes?.invoke()
        }
        return Message(kind, ts, key, payload)
    }
}

/** HEVC VPS/SPS/PPS: nalLen(1) count(1) then per set: length(2) bytes. */
class ParameterSets(val nalUnitHeaderLength: Int, val sets: List<ByteArray>) {
    companion object {
        fun decode(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nal = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var off = 2
            val out = ArrayList<ByteArray>(count)
            repeat(count) {
                if (d.size < off + 2) return null
                val len = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
                off += 2
                if (d.size < off + len) return null
                out.add(d.copyOfRange(off, off + len))
                off += len
            }
            return ParameterSets(nal, out)
        }
    }
}

/** Binary payloads: thumbnails, app icons, cursor shapes. */
object Blobs {
    class Thumbnail(val windowID: Long, val jpeg: ByteArray)
    class Icon(val bundleID: String, val png: ByteArray)
    class Cursor(val hotX: Float, val hotY: Float, val width: Float, val height: Float, val png: ByteArray)

    fun thumbnail(d: ByteArray): Thumbnail? {
        if (d.size <= 4) return null
        val id = ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xFFFFFFFFL
        return Thumbnail(id, d.copyOfRange(4, d.size))
    }

    fun icon(d: ByteArray): Icon? {
        if (d.size <= 2) return null
        val len = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        if (d.size <= 2 + len) return null
        return Icon(String(d, 2, len, Charsets.UTF_8), d.copyOfRange(2 + len, d.size))
    }

    fun cursor(d: ByteArray): Cursor? {
        if (d.size <= 16) return null
        val b = ByteBuffer.wrap(d)
        return Cursor(b.float, b.float, b.float, b.float, d.copyOfRange(16, d.size))
    }
}
