package dev.sash.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private val LoupeWidth = 144.dp
private val LoupeHeight = 96.dp
private val LoupeCorner = 18.dp
/** From the spot to the bubble's near edge: room for the fingertip in between. */
private val LoupeGap = 44.dp
/** How many times larger than the picture as drawn the loupe shows the Mac. */
private const val MAGNIFY = 2.5f
/** Screen pixels per Mac frame pixel the loupe stops at, unless the picture is drawn larger still. */
private const val MAX_PIXEL = 4f

/** Where a held finger aims, as a fraction of the picture, and whether it has grabbed (moving drags). */
data class LoupeSpot(val at: Pair<Double, Double>, val grabbed: Boolean = false)

/**
 * The loupe: while a finger holds the picture (a long press, aiming, a drag), a bubble beside it
 * shows the Mac around the [spot] magnified, with a crosshair on the exact point the click or drop
 * goes to, and an accent edge once it has grabbed. Its pixels are copied from the [surface]'s
 * decoded frame, [video] pixels in size, so they are sharper than the picture drawn into [rect];
 * [box] is the pane.
 */
@Composable
fun Loupe(spot: LoupeSpot?, surface: SurfaceView?, video: Pair<Int, Int>?, rect: VideoRect, box: IntSize) {
    if (spot == null || surface == null || video == null || rect.width <= 0f) return
    val at = spot.at
    val density = LocalDensity.current
    val w = with(density) { LoupeWidth.toPx() }
    val h = with(density) { LoupeHeight.toPx() }
    val gap = with(density) { LoupeGap.toPx() }

    // The frame's pixels in the bubble: as many as fit at the chosen magnification, kept inside the frame.
    val (vw, vh) = video
    val drawn = rect.width / vw
    val k = maxOf(drawn * 1.5f, minOf(drawn * MAGNIFY, MAX_PIXEL))
    val srcW = (w / k).roundToInt().coerceIn(8, vw)
    val srcH = (h / k).roundToInt().coerceIn(8, vh)
    val cx = at.first * vw
    val cy = at.second * vh
    val srcLeft = (cx - srcW / 2.0).roundToInt().coerceIn(0, vw - srcW)
    val srcTop = (cy - srcH / 2.0).roundToInt().coerceIn(0, vh - srcH)
    val src by rememberUpdatedState(Rect(srcLeft, srcTop, srcLeft + srcW, srcTop + srcH))
    // Off center only where the spot is near the frame's edge.
    val cross = Offset(((cx - srcLeft) / srcW * w).toFloat(), ((cy - srcTop) / srcH * h).toFloat())

    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(surface, srcW, srcH) {
        // Two bitmaps: one is drawn while the next frame is copied into the other.
        val bitmaps = Array(2) { Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888) }
        val handler = Handler(Looper.getMainLooper())
        var next = 0
        while (isActive) {
            val b = bitmaps[next]
            val ok = suspendCancellableCoroutine { done ->
                runCatching {
                    PixelCopy.request(surface, src, b, { r -> done.resume(r == PixelCopy.SUCCESS) }, handler)
                }.onFailure { done.resume(false) }
            }
            if (ok) { frame = b.asImageBitmap(); next = 1 - next }
            withFrameNanos { }
        }
    }

    // Above the finger, or beside it where the pane's top leaves no room.
    val px = rect.left + at.first.toFloat() * rect.width
    val py = rect.top + at.second.toFloat() * rect.height
    val above = py - gap - h >= 0f
    val left = when {
        above -> px - w / 2
        px > box.width / 2f -> px - gap - w
        else -> px + gap
    }.coerceIn(0f, maxOf(0f, box.width - w))
    val top = (if (above) py - gap - h else py - h / 2).coerceIn(0f, maxOf(0f, box.height - h))

    val shape = RoundedCornerShape(LoupeCorner)
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(LoupeWidth, LoupeHeight)
            .shadow(8.dp, shape).clip(shape).background(Color.Black)
            .border(
                if (spot.grabbed) 3.dp else 1.5.dp,
                if (spot.grabbed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                shape,
            ),
    ) {
        Canvas(Modifier.size(LoupeWidth, LoupeHeight)) {
            frame?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
            // The crosshair: dark under light, so it reads on any background, open at the spot itself.
            val arm = 9.dp.toPx()
            val hole = 3.dp.toPx()
            for ((color, width) in listOf(Color.Black.copy(alpha = 0.6f) to 3.dp.toPx(), Color.White to 1.5.dp.toPx())) {
                drawLine(color, cross - Offset(arm + hole, 0f), cross - Offset(hole, 0f), width)
                drawLine(color, cross + Offset(hole, 0f), cross + Offset(arm + hole, 0f), width)
                drawLine(color, cross - Offset(0f, arm + hole), cross - Offset(0f, hole), width)
                drawLine(color, cross + Offset(0f, hole), cross + Offset(0f, arm + hole), width)
            }
        }
    }
}
