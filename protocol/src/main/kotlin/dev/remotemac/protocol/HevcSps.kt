package dev.remotemac.protocol

/** The picture size from an HEVC SPS (ITU-T H.265 §7.3.2.2), conformance window applied. */
object HevcSps {
    fun size(nal: ByteArray): Pair<Int, Int>? = try {
        if (nal.size < 4 || ((nal[0].toInt() shr 1) and 0x3F) != 33) null else parse(unescape(nal))
    } catch (e: Exception) { null }

    private fun unescape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (i in 2 until b.size) {
            val v = b[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun bit(): Int { val v = (d[pos shr 3].toInt() shr (7 - (pos and 7))) and 1; pos++; return v }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun skip(n: Int) { pos += n }
        fun ue(): Int { var z = 0; while (bit() == 0) z++; return ((1 shl z) - 1) + bits(z) }
    }

    private fun parse(d: ByteArray): Pair<Int, Int> {
        val r = Bits(d)
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        r.skip(1)
        // profile_tier_level: the general part, then the sub-layer flags and parts.
        r.skip(2 + 1 + 5 + 32 + 4 + 43 + 1 + 8)
        val profilePresent = IntArray(maxSubLayersMinus1)
        val levelPresent = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) { profilePresent[i] = r.bit(); levelPresent[i] = r.bit() }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i] == 1) r.skip(88)
            if (levelPresent[i] == 1) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var w = r.ue()
        var h = r.ue()
        if (r.bit() == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val l = r.ue(); val rt = r.ue(); val t = r.ue(); val b = r.ue()
            w -= subW * (l + rt); h -= subH * (t + b)
        }
        return w to h
    }
}
