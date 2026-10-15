package dev.remotemac.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import dev.remotemac.app.stream.Controller
import dev.remotemac.app.stream.HidKeys
import dev.remotemac.protocol.PointerAction
import dev.remotemac.protocol.ScrollPhase
import dev.remotemac.protocol.TrackpadGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** Where the Mac's frame is drawn inside the input surface, in pixels. */
data class VideoRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun fraction(p: Offset): Pair<Double, Double> =
        ((p.x - left) / width).toDouble() to ((p.y - top) / height).toDouble()

    fun clamped(p: Offset): Pair<Double, Double> {
        val (x, y) = fraction(p)
        return x.coerceIn(0.0, 0.9999) to y.coerceIn(0.0, 0.9999)
    }

    fun contains(p: Offset) = p.x >= left && p.y >= top && p.x < left + width && p.y < top + height

    companion object {
        /** The frame aspect-fit (letterboxed) into a box. */
        fun fit(boxW: Float, boxH: Float, videoW: Int, videoH: Int): VideoRect {
            if (videoW <= 0 || videoH <= 0) return VideoRect(0f, 0f, boxW, boxH)
            val s = minOf(boxW / videoW, boxH / videoH)
            val w = videoW * s
            val h = videoH * s
            return VideoRect((boxW - w) / 2, (boxH - h) / 2, w, h)
        }
    }
}

/** ⌃ ⌥ ⇧ ⌘ latched from the key row: they apply to the next key or click, then let go. */
class ModifierLatch(private val state: MutableState<Long>) {
    val value get() = state.value
    fun toggle(bit: Long) { state.value = state.value xor bit }
    fun take(): Long = state.value.also { state.value = 0L }
}

private const val LONG_PRESS_MS = 450L
private const val MOMENTUM_MIN_DP_S = 150f

/** Momentum after a fling: steps at 60 Hz, ×0.96 a step, until under 0.3 dp a step. */
private class Momentum(private val scope: CoroutineScope, private val c: Controller, private val density: Float) {
    private var job: Job? = null
    private var at: Pair<Double, Double> = 0.0 to 0.0

    fun start(velocity: Offset, at: Pair<Double, Double>, rect: () -> VideoRect?) {
        stop()
        this.at = at
        job = scope.launch {
            c.scrollPhase(ScrollPhase.MOMENTUM_BEGAN, at.first, at.second)
            var v = velocity / 60f
            while (isActive && hypot(v.x, v.y) > 0.3f * density) {
                val r = rect() ?: break
                c.scroll(at.first, at.second, (v.x / r.width).toDouble(), (v.y / r.height).toDouble())
                v *= 0.96f
                delay(16)
            }
            c.scrollPhase(ScrollPhase.MOMENTUM_ENDED, at.first, at.second)
            job = null
        }
    }

    fun stop() {
        val j = job ?: return
        j.cancel()
        job = null
        c.scrollPhase(ScrollPhase.MOMENTUM_ENDED, at.first, at.second)
    }
}

/** Three or four fingers: a swipe or a pinch, decided as the fingers lift. */
private fun decideGesture(start: List<Offset>, end: List<Offset>, density: Float): String? {
    if (start.size < 3 || end.size != start.size) return null
    fun centroid(l: List<Offset>) = l.reduce { a, b -> a + b } / l.size.toFloat()
    fun spread(l: List<Offset>, c: Offset) = l.map { (it - c).getDistance() }.average().toFloat()
    val c0 = centroid(start)
    val c1 = centroid(end)
    val d = c1 - c0
    val s0 = spread(start, c0)
    val s1 = spread(end, c1)
    if (s0 > 0 && s1 / s0 < 0.75f) return TrackpadGesture.PINCH
    if (s0 > 0 && s1 / s0 > 1.25f) return TrackpadGesture.SPREAD
    if (hypot(d.x, d.y) < 40 * density) return null
    return when {
        abs(d.y) > abs(d.x) * 1.3f -> if (d.y < 0) TrackpadGesture.SWIPE_UP else TrackpadGesture.SWIPE_DOWN
        abs(d.x) > abs(d.y) * 1.3f -> if (d.x < 0) TrackpadGesture.SWIPE_LEFT else TrackpadGesture.SWIPE_RIGHT
        else -> null
    }
}

/** Press the latched modifiers' own keys around a click, as a keyboard held down would. */
private fun withModifiers(c: Controller, mods: Long, click: () -> Unit) {
    if (mods == 0L) return click()
    val keys = HidKeys.modifierKeys(mods)
    keys.forEach { (k, held) -> c.key(k, true, held) }
    click()
    keys.reversed().forEach { (k, _) -> c.key(k, false, 0) }
}

/**
 * Direct touch: the picture is the Mac's screen. A tap clicks there; a long press is a right click,
 * or, if the finger then moves, a drag; one or two fingers pan to scroll (with momentum); three or
 * four fingers make the Mac's trackpad gestures. A mouse, trackpad or stylus is the Mac's mouse.
 */
fun Modifier.directInput(c: Controller, rect: () -> VideoRect?, latch: ModifierLatch, scope: CoroutineScope): Modifier = this
    .pointerInput(Unit) {
        val momentum = Momentum(scope, c, density)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            momentum.stop()
            val r0 = rect() ?: return@awaitEachGesture
            if (down.type == PointerType.Mouse || down.type == PointerType.Stylus) {
                mouseGesture(c, rect, down, latch)
                return@awaitEachGesture
            }
            val startTime = android.os.SystemClock.uptimeMillis()
            val startPos = HashMap<Long, Offset>().apply { put(down.id.value, down.position) }
            var maxFingers = 1
            var scrolling = false
            var dragging = false
            var longFired = false
            var gestureStart: List<Offset>? = null
            var lastEnd: List<Offset> = emptyList()
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            var lastCentroid = down.position
            var scrollAt = r0.clamped(down.position)

            while (true) {
                val event = if (!longFired && !scrolling && maxFingers == 1) {
                    withTimeoutOrNull((LONG_PRESS_MS - (android.os.SystemClock.uptimeMillis() - startTime)).coerceAtLeast(1)) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (event == null) {
                    // Held still: a long press. Haptic now; lifting without moving is a right click.
                    longFired = true
                    hapticLong()
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                event.changes.forEach { ch -> if (ch.pressed && ch.id.value !in startPos) startPos[ch.id.value] = ch.position }
                maxFingers = maxOf(maxFingers, pressed.size)
                if (pressed.isNotEmpty()) lastEnd = pressed.map { it.position }

                if (maxFingers >= 3) {
                    if (gestureStart == null || (pressed.size > (gestureStart?.size ?: 0))) gestureStart = pressed.map { it.position }
                    if (scrolling) { c.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second); scrolling = false }
                    event.changes.forEach { it.consume() }
                    if (pressed.isEmpty() || pressed.size < (gestureStart?.size ?: 0)) {
                        val name = decideGesture(gestureStart ?: emptyList(), lastEnd.take(gestureStart?.size ?: 0), density)
                        if (name != null) c.gesture(name, maxFingers.coerceAtMost(4))
                        // Wait for every finger to lift before the next stroke.
                        while (event.changes.any { it.pressed }) { if (awaitPointerEvent().changes.none { it.pressed }) break }
                        return@awaitEachGesture
                    }
                    continue
                }

                val centroid = if (pressed.isEmpty()) lastCentroid else pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                val moved = pressed.any { ch -> ((startPos[ch.id.value] ?: ch.position) - ch.position).getDistance() > slop }

                if (longFired && maxFingers == 1) {
                    val p = pressed.firstOrNull()
                    if (p != null) {
                        val (x, y) = r0.clamped(p.position)
                        if (!dragging && moved) {
                            val (sx, sy) = r0.clamped(down.position)
                            c.move(sx, sy)
                            c.pointer(PointerAction.LEFT_DOWN, sx, sy)
                            dragging = true
                        }
                        if (dragging) c.move(x, y)
                        p.consume()
                    } else {
                        if (dragging) {
                            val (x, y) = r0.clamped(lastEnd.firstOrNull() ?: down.position)
                            c.pointer(PointerAction.LEFT_UP, x, y)
                        } else {
                            val (x, y) = r0.clamped(down.position)
                            if (r0.contains(down.position)) {
                                c.move(x, y)
                                withModifiers(c, latch.take()) {
                                    c.pointer(PointerAction.RIGHT_DOWN, x, y); c.pointer(PointerAction.RIGHT_UP, x, y)
                                }
                            }
                        }
                        return@awaitEachGesture
                    }
                    continue
                }

                if (!scrolling && moved && pressed.isNotEmpty()) {
                    scrolling = true
                    scrollAt = r0.clamped(centroid)
                    lastCentroid = centroid
                    c.move(scrollAt.first, scrollAt.second)
                    c.scrollPhase(ScrollPhase.BEGAN, scrollAt.first, scrollAt.second)
                }
                if (scrolling) {
                    if (pressed.isNotEmpty()) {
                        val d = centroid - lastCentroid
                        lastCentroid = centroid
                        tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                        val r = rect() ?: r0
                        if (d != Offset.Zero) c.scroll(scrollAt.first, scrollAt.second, (d.x / r.width).toDouble(), (d.y / r.height).toDouble())
                        event.changes.forEach { it.consume() }
                    } else {
                        c.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                        val v = tracker.calculateVelocity()
                        val speed = hypot(v.x, v.y) / density
                        if (speed > MOMENTUM_MIN_DP_S) momentum.start(Offset(v.x, v.y), scrollAt, rect)
                        return@awaitEachGesture
                    }
                    continue
                }

                if (pressed.isEmpty()) {
                    // A tap: one finger clicks where it lands, two fingers are a right click.
                    if (r0.contains(down.position)) {
                        val (x, y) = r0.clamped(down.position)
                        c.move(x, y)
                        withModifiers(c, latch.take()) {
                            if (maxFingers >= 2) { c.pointer(PointerAction.RIGHT_DOWN, x, y); c.pointer(PointerAction.RIGHT_UP, x, y) }
                            else { c.pointer(PointerAction.LEFT_DOWN, x, y); c.pointer(PointerAction.LEFT_UP, x, y) }
                        }
                    }
                    return@awaitEachGesture
                }
            }
        }
    }
    .pointerInput(Unit) {
        // Hover (a mouse, a trackpad or a stylus above the glass) and the wheel.
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.firstOrNull() ?: continue
                val r = rect() ?: continue
                when (e.type) {
                    PointerEventType.Move -> if (!ch.pressed && (ch.type == PointerType.Mouse || ch.type == PointerType.Stylus)) {
                        val (x, y) = r.clamped(ch.position); c.move(x, y)
                    }
                    PointerEventType.Scroll -> {
                        val (x, y) = r.clamped(ch.position)
                        val d = ch.scrollDelta
                        // A wheel notch is ~1 unit: about 40 dp of content.
                        c.scroll(x, y, (-d.x * 40 * density / r.width).toDouble(), (-d.y * 40 * density / r.height).toDouble())
                        ch.consume()
                    }
                    else -> {}
                }
            }
        }
    }

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.mouseGesture(
    c: Controller, rect: () -> VideoRect?, down: PointerInputChange, latch: ModifierLatch,
) {
    val r = rect() ?: return
    val (x, y) = r.clamped(down.position)
    val secondary = currentEvent.buttons.isSecondaryPressed
    val mods = latch.take()
    val keys = HidKeys.modifierKeys(mods)
    keys.forEach { (k, held) -> c.key(k, true, held) }
    c.move(x, y)
    c.pointer(if (secondary) PointerAction.RIGHT_DOWN else PointerAction.LEFT_DOWN, x, y)
    var last = x to y
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.id == down.id } ?: e.changes.first()
        val rr = rect() ?: r
        last = rr.clamped(ch.position)
        if (!ch.pressed) break
        c.move(last.first, last.second)
        ch.consume()
    }
    c.pointer(if (secondary) PointerAction.RIGHT_UP else PointerAction.LEFT_UP, last.first, last.second)
    keys.reversed().forEach { (k, _) -> c.key(k, false, 0) }
}

/** Set by the screen so a long press can buzz without a View in the gesture code. */
var hapticLong: () -> Unit = {}

/**
 * The trackpad (a phone held upright): one finger moves the Mac's pointer from where it is, a tap
 * clicks, two fingers tap for a right click and pan to scroll, a long press then drag holds the
 * button down, three or four fingers make the Mac's gestures.
 */
fun Modifier.trackpadInput(
    c: Controller,
    span: () -> Pair<Float, Float>,
    cursor: MutableState<Pair<Double, Double>>,
    latch: ModifierLatch,
    scope: CoroutineScope,
): Modifier = this.pointerInput(Unit) {
    val momentum = Momentum(scope, c, density)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        momentum.stop()
        val startTime = android.os.SystemClock.uptimeMillis()
        val start = HashMap<Long, Offset>().apply { put(down.id.value, down.position) }
        var last = HashMap<Long, Offset>().apply { put(down.id.value, down.position) }
        var maxFingers = 1
        var moving = false
        var scrolling = false
        var holding = false
        var longFired = false
        var gestureStart: List<Offset>? = null
        var lastPressed: List<Offset> = listOf(down.position)
        val slop = 2 * density
        val tracker = VelocityTracker()
        var scrollCentroid = down.position

        fun sendMove() { c.move(cursor.value.first, cursor.value.second) }

        while (true) {
            val event = if (!longFired && !moving && !scrolling && maxFingers == 1) {
                withTimeoutOrNull((LONG_PRESS_MS - (android.os.SystemClock.uptimeMillis() - startTime)).coerceAtLeast(1)) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (event == null) {
                longFired = true
                holding = true
                hapticLong()
                c.pointer(PointerAction.LEFT_DOWN, cursor.value.first, cursor.value.second)
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            event.changes.forEach { ch -> if (ch.pressed && ch.id.value !in start) { start[ch.id.value] = ch.position; last[ch.id.value] = ch.position } }
            maxFingers = maxOf(maxFingers, pressed.size)
            if (pressed.isNotEmpty()) lastPressed = pressed.map { it.position }
            event.changes.forEach { it.consume() }

            if (maxFingers >= 3) {
                if (gestureStart == null || pressed.size > (gestureStart?.size ?: 0)) gestureStart = pressed.map { it.position }
                if (scrolling) { c.scrollPhase(ScrollPhase.ENDED, cursor.value.first, cursor.value.second); scrolling = false }
                if (pressed.size < (gestureStart?.size ?: 0)) {
                    decideGesture(gestureStart!!, lastPressed.take(gestureStart!!.size), density)?.let { c.gesture(it, maxFingers.coerceAtMost(4)) }
                    while (true) { if (awaitPointerEvent().changes.none { it.pressed }) break }
                    return@awaitEachGesture
                }
                continue
            }

            if (maxFingers == 2) {
                val centroid = if (pressed.isEmpty()) scrollCentroid else pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                val movedFar = pressed.any { ((start[it.id.value] ?: it.position) - it.position).getDistance() > viewConfiguration.touchSlop }
                if (!scrolling && movedFar && pressed.size == 2) {
                    scrolling = true
                    scrollCentroid = centroid
                    c.scrollPhase(ScrollPhase.BEGAN, cursor.value.first, cursor.value.second)
                }
                if (scrolling && pressed.isNotEmpty()) {
                    val d = centroid - scrollCentroid
                    scrollCentroid = centroid
                    tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                    val (w, h) = span()
                    c.scroll(cursor.value.first, cursor.value.second, (d.x / w).toDouble(), (d.y / h).toDouble())
                }
                if (pressed.isEmpty()) {
                    if (scrolling) {
                        c.scrollPhase(ScrollPhase.ENDED, cursor.value.first, cursor.value.second)
                        val v = tracker.calculateVelocity()
                        if (hypot(v.x, v.y) / density > MOMENTUM_MIN_DP_S) {
                            val (w, h) = span()
                            momentum.start(Offset(v.x, v.y), cursor.value) { VideoRect(0f, 0f, w, h) }
                        }
                    } else if (android.os.SystemClock.uptimeMillis() - startTime < LONG_PRESS_MS) {
                        withModifiers(c, latch.take()) {
                            c.pointer(PointerAction.RIGHT_DOWN, cursor.value.first, cursor.value.second)
                            c.pointer(PointerAction.RIGHT_UP, cursor.value.first, cursor.value.second)
                        }
                    }
                    return@awaitEachGesture
                }
                continue
            }

            // One finger: relative motion.
            val p = pressed.firstOrNull()
            if (p != null) {
                val prev = last[p.id.value] ?: p.position
                if (!moving && (p.position - (start[p.id.value] ?: p.position)).getDistance() > slop) moving = true
                if (moving) {
                    val d = p.position - prev
                    val (w, h) = span()
                    val nx = (cursor.value.first + d.x / w * 1.25).coerceIn(0.0, 0.9999)
                    val ny = (cursor.value.second + d.y / h * 1.25).coerceIn(0.0, 0.9999)
                    cursor.value = nx to ny
                    sendMove()
                }
                last[p.id.value] = p.position
            } else {
                if (holding) {
                    c.pointer(PointerAction.LEFT_UP, cursor.value.first, cursor.value.second)
                } else if (!moving) {
                    sendMove()
                    withModifiers(c, latch.take()) {
                        c.pointer(PointerAction.LEFT_DOWN, cursor.value.first, cursor.value.second)
                        c.pointer(PointerAction.LEFT_UP, cursor.value.first, cursor.value.second)
                    }
                }
                return@awaitEachGesture
            }
        }
    }
}
