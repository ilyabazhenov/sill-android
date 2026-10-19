package dev.sash.protocol

import kotlin.math.abs

/**
 * What a cursor image (kind 14) shows. The host sends only the picture and its hotspot, so the
 * shape is read off the pixels: the text I-beam is a narrow upright stroke, inked through its
 * middle, capped wider at both ends, with the hotspot at its centre. That tells it from the arrow
 * and hand (hotspot near an edge), the up–down resize arrows (a gap or a bar at the middle) and
 * the rest (test fixtures: tools/cursors).
 */
object CursorShape {
    /** [opaque] is row-major, [w]×[h] pixels; the hotspot is in the same pixels. */
    fun isIBeam(opaque: BooleanArray, w: Int, h: Int, hotX: Float, hotY: Float): Boolean {
        if (w <= 0 || h <= 0 || opaque.size < w * h) return false
        val span = IntArray(h)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            var a = -1; var b = -1
            for (x in 0 until w) if (opaque[y * w + x]) { if (a < 0) a = x; b = x }
            if (a < 0) continue
            span[y] = b - a + 1
            if (top < 0) top = y
            bottom = y
            if (a < left) left = a
            if (b > right) right = b
        }
        if (top < 0) return false
        val bw = right - left + 1
        val bh = bottom - top + 1
        if (bh < 8 || bw > bh * 0.6f) return false
        if (abs(hotX - (left + right + 1) / 2f) > bw * 0.25f) return false
        if (abs(hotY - (top + bottom + 1) / 2f) > bh * 0.2f) return false

        val widest = span.max()
        var stem = 0
        for (y in top + (bh * 0.3f).toInt()..top + (bh * 0.7f).toInt()) {
            if (span[y] == 0 || span[y] > widest * 0.6f) return false
            stem = maxOf(stem, span[y])
        }
        val cap = (bh * 0.15f).toInt().coerceAtLeast(1)
        val capTop = (top until top + cap).maxOf { span[it] }
        val capBottom = (bottom - cap + 1..bottom).maxOf { span[it] }
        return capTop >= stem * 1.6f && capBottom >= stem * 1.6f
    }
}
