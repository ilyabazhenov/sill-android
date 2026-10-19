package dev.sash.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Mac's own cursors, as tools/cursors/dump.swift printed them (macOS 26, plus the older I-beam). */
class CursorShapeTest {
    private val names = listOf(
        "arrow", "closedHand", "columnResize", "contextMenu", "crosshair", "disappearing", "dragCopy", "dragLink",
        "frameResizeLeft", "frameResizeTop", "frameResizeTopLeft", "iBeam", "iBeamLegacy", "iBeamV", "notAllowed",
        "openHand", "pointingHand", "resizeDown", "resizeLeftRight", "resizeUp", "resizeUpDown", "rowResize", "zoomIn",
    )

    private fun isIBeam(name: String): Boolean {
        val lines = javaClass.getResource("/cursors/$name.txt")!!.readText().trim().lines()
        // name ptW ptH hotX hotY pxW pxH
        val h = lines[0].split(" ")
        val pxW = h[5].toInt()
        val pxH = h[6].toInt()
        val rows = lines.drop(1)
        assertEquals(pxH, rows.size)
        val opaque = BooleanArray(pxW * pxH) { rows[it / pxW][it % pxW] == '1' }
        val k = pxW / h[1].toFloat()
        return CursorShape.isIBeam(opaque, pxW, pxH, h[3].toFloat() * k, h[4].toFloat() * k)
    }

    @Test fun onlyTheIBeamsAreText() {
        val text = names.filter { isIBeam(it) }
        assertEquals(listOf("iBeam", "iBeamLegacy"), text)
    }
}
