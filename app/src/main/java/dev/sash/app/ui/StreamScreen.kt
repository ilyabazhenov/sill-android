package dev.sash.app.ui

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import android.view.HapticFeedbackConstants
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sash.app.MainActivity
import dev.sash.app.R
import dev.sash.app.stream.BarPosition
import dev.sash.app.stream.Controller
import dev.sash.app.stream.orderedWindows
import dev.sash.app.stream.HidKeys
import dev.sash.app.stream.Phase
import dev.sash.app.stream.Target
import dev.sash.protocol.Modifiers
import dev.sash.protocol.StreamSource
import dev.sash.protocol.Viewport
import dev.sash.protocol.WindowCommand
import dev.sash.protocol.WindowInfo
import kotlin.math.roundToInt

private val BarColor = Color(0xFF111317)
private val ChipColor = Color(0xFF22252D)
/** A hairline between the bar's parts and around the trackpad. */
private val Hairline = Color(0xFF262A32)
private val Ink = Color(0xFFE7E9EE)
private val InkDim = Color(0xFF8A909B)
private val Accent = Color(0xFF4C8DFF)
/** Chips over the picture: the zoom, the bar's handle. */
private val Glass = Color(0xE6111317)
private val BarHeight = 64.dp
/** The key row's height: its keys and the padding around them. */
private val KeyRowHeight = 48.dp
/** How long the bar over a sideways picture stays after it was last touched. */
private const val BAR_HIDE_MS = 4_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamScreen(c: Controller, activity: MainActivity) {
    val view = LocalView.current
    LaunchedEffect(Unit) { hapticLong = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }

    val windows by c.windows.collectAsState()
    val phase by c.phase.collectAsState()
    val status by c.status.collectAsState()
    val videoSize by c.videoSize.collectAsState()
    val noPicture by c.noPicture.collectAsState()
    val scope = rememberCoroutineScope()
    val latchState = remember { mutableStateOf(0L) }
    val latch = remember { ModifierLatch(latchState) }
    var keyboardUp by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var scale by remember { mutableStateOf<Double?>(null) }
    val trackCursor = remember { mutableStateOf(0.5 to 0.5) }
    val showStats = remember { mutableStateOf(false) }
    val trackpadMode by c.trackpadMode.collectAsState()
    val barAt by c.barPosition.collectAsState()
    /** Whether the layout is the tall one, where the bar's place can be chosen. */
    var tallLayout by remember { mutableStateOf(false) }
    /** Window y where the keyboard begins and where the key row under the bar ends: the picture is lifted between them. */
    var imeTop by remember { mutableStateOf<Float?>(null) }
    var keysBottom by remember { mutableStateOf<Float?>(null) }
    var leaving by remember { mutableStateOf(false) }

    // A click into text offers the keyboard by it, unless a hardware one is there to type on. Only
    // offered: fields the Mac's apps autofocus would bring it up at every switch otherwise.
    val config = LocalConfiguration.current
    val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val offerKeyboard: (() -> Unit)? = if (hardKeyboard || keyboardUp) null else ({ c.dismissTextHint(); keyboardUp = true })
    // The keyboard put away by the system (Back, its own hide key) is closed here too.
    val imeUp = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) { if (imeUp) imeSeen = true else if (imeSeen) { imeSeen = false; keyboardUp = false } }

    // The shape of the Mac's screen, kept from the last picture of the whole desktop.
    var deskAspect by remember { mutableFloatStateOf(16f / 10f) }
    LaunchedEffect(videoSize, windows?.active) {
        val (w, h) = videoSize ?: return@LaunchedEffect
        if (windows?.active == StreamSource.Desktop && w > 0 && h > 0) deskAspect = w.toFloat() / h
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth && maxWidth < 600.dp
        // A tall screen too wide for the phone layout (a foldable's inner one held upright): the
        // picture the shape of the Mac's screen under the bar, and a trackpad in the room below it.
        val tall = !portrait && maxHeight - BarHeight - maxWidth / deskAspect >= 200.dp
        SideEffect { tallLayout = tall }
        // Pinch zoom of the picture; each new picture starts fitted.
        val zoom = remember(portrait) { PictureZoom() }
        LaunchedEffect(zoom, windows?.active) { zoom.reset() }
        // Back puts the keyboard away, then the zoom, and only then asks to disconnect.
        BackHandler {
            when {
                keyboardUp -> keyboardUp = false
                zoom.zoomed -> zoom.reset()
                else -> leaving = true
            }
        }
        val content: @Composable () -> Unit = {
            if (portrait) {
                // Zoomed in, the picture is cut at its pane, and the view follows the pointer the pad moves.
                val margin = with(LocalDensity.current) { 48.dp.toPx() }
                LaunchedEffect(zoom, margin) {
                    snapshotFlow { trackCursor.value }.collect { zoom.follow(it, margin) }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clipToBounds()) {
                        VideoPane(c, latch, scope, scale, trackCursor.value, showStats.value, null, zoom = zoom, offerKeyboard = offerKeyboard)
                    }
                    ButtonRow(c, keyboardUp, onKeyboard = { keyboardUp = !keyboardUp }, onSheet = { sheet = it }, scale = scale, onScale = { scale = it })
                    WindowStrip(c, Modifier.fillMaxWidth().height(64.dp))
                    KeyRow(c, latch, latchState.value, Modifier.fillMaxWidth())
                    Trackpad(c, latch, scope, trackCursor, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
                    if (keyboardUp) Spacer(Modifier.height(1.dp))
                }
            } else if (tall) {
                // The bar sits where the settings put it: above the picture, between it and the key
                // row, or under the trackpad. The key row sits on the trackpad, for modifier clicks;
                // with the keyboard up, which covers the trackpad, it moves to the top (under a bar
                // there), and a bar from the middle or the bottom rides on the keyboard. The picture
                // keeps its room and is lifted only if the keyboard, or the bar on it, reaches it.
                val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                val bar: @Composable () -> Unit = {
                    TopBar(c, Modifier, keyboardUp, { keyboardUp = !keyboardUp }, { sheet = it }, scale, { scale = it })
                }
                val barTop = barAt == BarPosition.TOP
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(sides)) {
                        // With the keyboard up the picture may be lifted into the room above it: the
                        // bar and the key row there are drawn after it (below), over it, and keep
                        // their room here.
                        if (barTop) { if (keyboardUp) Spacer(Modifier.height(BarHeight)) else bar() }
                        if (keyboardUp) Spacer(Modifier.height(KeyRowHeight))
                        Box(Modifier.fillMaxWidth().aspectRatio(deskAspect)) {
                            VideoPane(
                                c, latch, scope, scale, trackCursor.value, showStats.value,
                                trackCursor.takeIf { trackpadMode }, visibleBottom = if (keyboardUp) imeTop else null, zoom = zoom,
                                offerKeyboard = offerKeyboard,
                            )
                        }
                        if (barAt == BarPosition.MIDDLE && !keyboardUp) bar()
                        if (!keyboardUp) KeyRow(c, latch, latchState.value, Modifier.fillMaxWidth())
                        // Under the keyboard the trackpad can't be used, and its top edge would peek
                        // out above it: the same room stays, empty, so nothing above moves.
                        if (keyboardUp) Spacer(Modifier.weight(1f).fillMaxWidth())
                        else Trackpad(c, latch, scope, trackCursor, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
                        if (barAt == BarPosition.BOTTOM && !keyboardUp) bar()
                    }
                    if (keyboardUp) {
                        Column(Modifier.fillMaxWidth().windowInsetsPadding(sides.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                            if (barTop) bar()
                            KeyRow(c, latch, latchState.value, Modifier.fillMaxWidth())
                        }
                        val onKeyboard = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .onGloballyPositioned { imeTop = it.positionInWindow().y }
                        if (barTop) Spacer(onKeyboard) else Box(onKeyboard) { bar() }
                    }
                }
            } else {
                // The picture has the whole screen; the bar floats over its top and slides away a few
                // seconds after it was last touched, or as soon as the picture is, leaving a handle that
                // brings it back. The picture keeps its room with the keyboard up too (the Mac sizes its
                // windows to that room); it is lifted instead. The bar and the key row under it come
                // after it so they draw over the part lifted out of its room; nothing sits on the keyboard.
                val barInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                var barShown by remember { mutableStateOf(true) }
                var barTouched by remember { mutableLongStateOf(0L) }
                var barMenus by remember { mutableIntStateOf(0) }
                LaunchedEffect(barShown, barTouched, barMenus, sheet) {
                    if (barShown && barMenus == 0 && sheet == null) { kotlinx.coroutines.delay(BAR_HIDE_MS); barShown = false }
                }
                val onMenu: (Boolean) -> Unit = { open -> barMenus += if (open) 1 else -1 }
                // Where the fitted picture leaves a black band above it wide enough for the bar (a
                // squarer screen, such as a foldable's inner one), the bar stays put: it hides nothing.
                val density = LocalDensity.current
                val barFits = !zoom.zoomed && videoSize?.let { (w, h) ->
                    val fit = with(density) { VideoRect.fit(maxWidth.toPx(), maxHeight.toPx(), w, h) }
                    fit.top >= barInsets.getTop(density) + with(density) { BarHeight.toPx() }
                } == true
                val barUp = barShown || barFits
                val barRoom by animateDpAsState(if (barUp) BarHeight else 0.dp, label = "bar")
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); barShown = false }
                        },
                    ) {
                        VideoPane(
                            c, latch, scope, scale, if (trackpadMode) trackCursor.value else null, showStats.value,
                            trackCursor.takeIf { trackpadMode }, visibleTop = if (keyboardUp) keysBottom else null,
                            visibleBottom = if (keyboardUp) imeTop else null, zoom = zoom, offerKeyboard = offerKeyboard,
                        )
                    }
                    AnimatedVisibility(
                        barUp,
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        TopBar(
                            c,
                            Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) { awaitPointerEvent(PointerEventPass.Initial); barTouched = SystemClock.uptimeMillis() }
                                }
                            }.windowInsetsPadding(barInsets),
                            keyboardUp, { keyboardUp = !keyboardUp }, { sheet = it }, scale, { scale = it }, onMenu,
                        )
                    }
                    if (!barUp) {
                        BarHandle(
                            Modifier.align(Alignment.TopCenter).windowInsetsPadding(barInsets)
                                .padding(top = if (keyboardUp) KeyRowHeight else 0.dp),
                        ) { barShown = true }
                    }
                    if (keyboardUp) {
                        KeyRow(
                            c, latch, latchState.value,
                            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                                .padding(top = barRoom).background(BarColor)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .onGloballyPositioned { keysBottom = it.positionInWindow().y + it.size.height },
                        )
                        Spacer(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                                .onGloballyPositioned { imeTop = it.positionInWindow().y },
                        )
                    }
                }
            }
        }
        content()

        HiddenKeyboard(c, latch, keyboardUp, onClosed = { keyboardUp = false })

        if (phase == Phase.RECONNECTING) {
            Box(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(16.dp),
            ) { Text(status ?: stringResource(R.string.reconnecting), color = Color.White) }
        } else if (noPicture) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.no_picture, windows?.macName ?: stringResource(R.string.the_mac)), color = Color.White)
                TextButton({ c.retryPicture() }) { Text(stringResource(R.string.try_again)) }
            }
        }
        ClipboardChip(c, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp))
    }

    if (leaving) {
        val mac = windows?.macName ?: stringResource(R.string.the_mac)
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.disconnect_title, mac)) },
            text = { Text(stringResource(R.string.disconnect_body, mac)) },
            confirmButton = { TextButton({ leaving = false; c.disconnect() }) { Text(stringResource(R.string.disconnect)) } },
            dismissButton = { TextButton({ leaving = false }) { Text(stringResource(R.string.stay)) } },
        )
    }

    // Keep the viewport the Mac sizes windows to in step with the picture's room and the Aa scale.
    when (sheet) {
        Sheet.SETTINGS -> SettingsSheet(c, showStats, tallLayout, onDismiss = { sheet = null })
        Sheet.APPS -> AppsSheet(c, activity, onDismiss = { sheet = null })
        Sheet.MENUS -> MenusSheet(c, onDismiss = { sheet = null })
        null -> {}
    }
}

enum class Sheet { SETTINGS, APPS, MENUS }

/**
 * The Mac's picture, letterboxed, with the Mac's pointer drawn over it. Touch on it is direct, or,
 * given a [trackpad] cursor, relative: the picture works as a trackpad moving that cursor. Given a
 * [visibleBottom] (window y) inside it, the picture is lifted to keep the last click above it, and
 * below [visibleTop] when that is given too.
 */
@Composable
private fun VideoPane(
    c: Controller,
    latch: ModifierLatch,
    scope: kotlinx.coroutines.CoroutineScope,
    scale: Double?,
    ownPointer: Pair<Double, Double>?,
    showStats: Boolean,
    trackpad: androidx.compose.runtime.MutableState<Pair<Double, Double>>?,
    visibleTop: Float? = null,
    visibleBottom: Float? = null,
    zoom: PictureZoom? = null,
    offerKeyboard: (() -> Unit)? = null,
) {
    val videoSize by c.videoSize.collectAsState()
    val textHint by c.textHint.collectAsState()
    val macPointer by c.macPointer.collectAsState()
    val cursor by c.cursor.collectAsState()
    val settings by c.settings.collectAsState()
    val target by c.target.collectAsState()
    val lastClick by c.lastClick.collectAsState()
    val density = LocalDensity.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var boxTop by remember { mutableFloatStateOf(0f) }
    val fit = videoSize?.let { (w, h) -> VideoRect.fit(box.width.toFloat(), box.height.toFloat(), w, h) }
        ?: VideoRect(0f, 0f, box.width.toFloat(), box.height.toFloat())
    val base = zoom?.apply(fit, box.width.toFloat(), box.height.toFloat()) ?: fit

    // Lifted so the last click (the text field being typed into) sits in sight above the keys: moved
    // only as far as that needs, and never so far that the picture's bottom edge clears the keys.
    val lift = remember { mutableFloatStateOf(0f) }
    val wantLift = run {
        val bottom = (visibleBottom ?: return@run 0f) - boxTop
        if (bottom >= box.height) return@run 0f
        val top = ((visibleTop ?: boxTop) - boxTop).coerceIn(0f, bottom)
        val at = lastClick ?: macPointer ?: (0.5 to 0.5)
        val y = base.top + at.second.toFloat() * base.height
        val margin = with(density) { 24.dp.toPx() }
        val lo = y - (bottom - maxOf(2 * margin, (bottom - top) * 0.35f))
        val hi = y - top - margin
        val l = if (lo >= hi) lo else lift.floatValue.coerceIn(lo, hi)
        l.coerceIn(0f, maxOf(0f, base.top + base.height - bottom))
    }
    SideEffect { lift.floatValue = wantLift }
    val lifted by animateFloatAsState(wantLift, label = "lift")
    val rect = if (lifted == 0f) base else base.copy(top = base.top - lifted)
    val rectRef = remember { mutableStateOf<VideoRect?>(null) }
    rectRef.value = if (box.width > 0) rect else null
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    val loupe = remember { mutableStateOf<LoupeSpot?>(null) }

    // The viewport: this pane's size in dp, the Aa scale, and the display's refresh rate.
    val refresh = LocalView.current.display?.refreshRate?.roundToInt() ?: 60
    val remote = target is Target.Remote
    LaunchedEffect(box, scale, remote) {
        if (box.width == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        with(density) {
            c.viewport(
                Viewport(
                    width = box.width.toDp().value.toDouble(),
                    height = box.height.toDp().value.toDouble(),
                    scale = scale,
                    localCursor = true,
                    fps = if (remote) 60 else refresh.coerceIn(30, 120),
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { box = it }.onGloballyPositioned { boxTop = it.positionInWindow().y }) {
        // Placed by hand: zoomed in, the picture is larger than the pane and runs off its edges.
        AndroidView(
            modifier = Modifier.layout { m, room ->
                val p = m.measure(Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
                layout(room.maxWidth, room.maxHeight) { p.place(rect.left.roundToInt(), rect.top.roundToInt()) }
            },
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { c.decoder.attach(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { c.decoder.attach(null) }
                    })
                }.also { surfaceView = it }
            },
        )
        key(trackpad != null) {
            if (trackpad != null) {
                // The pointer carries on from wherever the Mac's pointer last was.
                LaunchedEffect(macPointer) { macPointer?.let { trackpad.value = it } }
                Box(
                    Modifier.fillMaxSize()
                        .trackpadInput(c, { rectRef.value?.let { it.width to it.height } ?: (1f to 1f) }, trackpad, latch, scope, zoom) { rectRef.value }
                        .hoverInput(c) { rectRef.value },
                )
            } else {
                Box(Modifier.fillMaxSize().directInput(c, { rectRef.value }, latch, scope, zoom, loupe))
            }
        }

        val at = macPointer ?: ownPointer
        val cur = cursor
        if (at != null && cur != null && videoSize != null) {
            val captureScale = settings?.settings?.captureScale ?: 2.0
            val framePoints = videoSize!!.first / captureScale
            val k = (rect.width / framePoints).toFloat()
            Canvas(Modifier.fillMaxSize()) {
                val x = rect.left + at.first.toFloat() * rect.width - cur.hotX * k
                val y = rect.top + at.second.toFloat() * rect.height - cur.hotY * k
                drawImage(
                    cur.image,
                    dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                    dstSize = IntSize((cur.width * k).roundToInt().coerceAtLeast(1), (cur.height * k).roundToInt().coerceAtLeast(1)),
                )
            }
        }
        if (showStats) StatsLine(c, Modifier.align(Alignment.TopEnd).padding(8.dp))
        val clickAt = lastClick
        if (textHint && offerKeyboard != null && clickAt != null && box.width > 0) {
            KeyboardOffer(rect.left + clickAt.first.toFloat() * rect.width, rect.top + clickAt.second.toFloat() * rect.height, box, offerKeyboard)
        }
        if (zoom?.zoomed == true) {
            ZoomChip(
                zoom.scale,
                Modifier.align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start)).padding(12.dp),
            ) { zoom.reset() }
        }
        Loupe(loupe.value, surfaceView, videoSize, rect, box)
    }
}

/**
 * The keyboard offered after a click into text: a button just above the click at [x], [y] (in the
 * pane's [box]), or below it where the pane's top leaves no room, kept clear of the spot clicked.
 */
@Composable
private fun KeyboardOffer(x: Float, y: Float, box: IntSize, onClick: () -> Unit) {
    val density = LocalDensity.current
    val size = with(density) { 44.dp.toPx() }
    val gap = with(density) { 20.dp.toPx() }
    val left = (x - size / 2).coerceIn(0f, maxOf(0f, box.width - size))
    val top = (if (y - gap - size >= 0f) y - gap - size else y + gap).coerceIn(0f, maxOf(0f, box.height - size))
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }.size(44.dp)
            .shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Keyboard, stringResource(R.string.keyboard), tint = MaterialTheme.colorScheme.onPrimary) }
}

@Composable
private fun StatsLine(c: Controller, modifier: Modifier) {
    val s by c.stats.collectAsState()
    val route by c.route.collectAsState()
    Text(
        stringResource(R.string.stats_line, s.fps, if (s.frameAgeMs < 0) "–" else stringResource(R.string.ms, s.frameAgeMs), if (s.rttMs < 0) "–" else stringResource(R.string.ms, s.rttMs)) + (route?.let { " · $it" } ?: ""),
        modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White, fontSize = 12.sp,
    )
}

@Composable
private fun BarButton(icon: ImageVector, label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 2.dp).size(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (lit) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = if (lit) Accent else Ink) }
}

@Composable
private fun MenusButton(c: Controller, onOpen: () -> Unit) {
    val menus by c.menus.collectAsState()
    if (menus.top.isEmpty()) return
    BarButton(Icons.Default.Menu, stringResource(R.string.menus_of, menus.app ?: stringResource(R.string.the_app)), onClick = onOpen)
}

/** Aa: how large the Mac's text renders here. Off leaves the Mac's windows their own size. */
@Composable
private fun ScaleButton(scale: Double?, onMenu: (Boolean) -> Unit = {}, onScale: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    MenuOpen(open, onMenu)
    Box {
        Box(
            Modifier.padding(horizontal = 2.dp).size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (scale != null) Accent.copy(alpha = 0.18f) else Color.Transparent)
                .combinedClickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.TextFields, stringResource(R.string.text_size), Modifier.size(22.dp), tint = if (scale != null) Accent else Ink) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.leave_windows)) }, onClick = { onScale(null); open = false })
            listOf(0.5, 0.75, 1.0, 1.25, 1.5).forEach { s ->
                DropdownMenuItem(
                    { Text("${s}× ${if (s == 1.0) stringResource(R.string.fit_window) else ""}") },
                    onClick = { onScale(s); open = false },
                    trailingIcon = { if (scale == s) Text("✓") },
                )
            }
        }
    }
}

/** The bar across a wide screen's top: Apps, the window strip, Menus, Aa, trackpad mode, Keyboard, Desktop, Settings. */
@Composable
private fun TopBar(
    c: Controller,
    modifier: Modifier,
    keyboardUp: Boolean,
    onKeyboard: () -> Unit,
    onSheet: (Sheet) -> Unit,
    scale: Double?,
    onScale: (Double?) -> Unit,
    onMenu: (Boolean) -> Unit = {},
) {
    val windows by c.windows.collectAsState()
    val trackpadMode by c.trackpadMode.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(BarColor).then(modifier).height(BarHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Default.Apps, stringResource(R.string.apps)) { onSheet(Sheet.APPS) }
        WindowStrip(c, Modifier.weight(1f).fillMaxHeight(), onMenu)
        MenusButton(c) { onSheet(Sheet.MENUS) }
        ScaleButton(scale, onMenu, onScale)
        BarButton(Icons.Default.Mouse, if (trackpadMode) stringResource(R.string.trackpad_on) else stringResource(R.string.trackpad_off), lit = trackpadMode) { c.setTrackpadMode(!trackpadMode) }
        BarButton(Icons.Default.Keyboard, stringResource(R.string.keyboard), lit = keyboardUp, onClick = onKeyboard)
        BarButton(Icons.Default.DesktopMac, stringResource(R.string.desktop), lit = windows?.active == StreamSource.Desktop) { c.select(StreamSource.Desktop) }
        BarButton(Icons.Default.Settings, stringResource(R.string.settings)) { onSheet(Sheet.SETTINGS) }
    }
}

/** Upright: Apps, Aa, Keyboard, Desktop, Menus, Settings across one row. */
@Composable
private fun ButtonRow(
    c: Controller,
    keyboardUp: Boolean,
    onKeyboard: () -> Unit,
    onSheet: (Sheet) -> Unit,
    scale: Double?,
    onScale: (Double?) -> Unit,
) {
    val windows by c.windows.collectAsState()
    Row(
        Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Default.Apps, stringResource(R.string.apps)) { onSheet(Sheet.APPS) }
        ScaleButton(scale, onScale = onScale)
        BarButton(Icons.Default.Keyboard, stringResource(R.string.keyboard), lit = keyboardUp, onClick = onKeyboard)
        BarButton(Icons.Default.DesktopMac, stringResource(R.string.desktop), lit = windows?.active == StreamSource.Desktop) { c.select(StreamSource.Desktop) }
        MenusButton(c) { onSheet(Sheet.MENUS) }
        BarButton(Icons.Default.Settings, stringResource(R.string.settings)) { onSheet(Sheet.SETTINGS) }
    }
}

/** Live thumbnails of the Mac's windows. Tap to stream one; hold for its traffic lights. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowStrip(c: Controller, modifier: Modifier, onMenu: (Boolean) -> Unit = {}) {
    val windows by c.windows.collectAsState()
    val thumbs by c.thumbnails.collectAsState()
    val icons by c.icons.collectAsState()
    val appOrder by c.appOrder.collectAsState()
    val list = remember(windows?.windows, appOrder) { orderedWindows(windows?.windows ?: emptyList(), appOrder) }
    val active = (windows?.active as? StreamSource.Window)?.id
    LazyRow(
        modifier.background(BarColor).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(list, key = { it.id }) { w ->
            WindowThumb(c, w, thumbs[w.id], icons[w.bundleID], w.id == active, onMenu)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowThumb(c: Controller, w: WindowInfo, thumb: ImageBitmap?, icon: ImageBitmap?, active: Boolean, onMenu: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    MenuOpen(menu, onMenu)
    val view = LocalView.current
    Box(
        Modifier.height(50.dp).width(80.dp).clip(RoundedCornerShape(10.dp))
            .background(ChipColor)
            .border(if (active) 2.dp else 1.dp, if (active) Accent else Hairline, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { c.select(StreamSource.Window(w.id)) },
                onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); menu = true },
            ),
    ) {
        if (thumb != null) Image(thumb, w.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(w.appName, Modifier.align(Alignment.Center).padding(4.dp), color = InkDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (icon != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(22.dp).background(Glass, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Image(icon, null, Modifier.size(17.dp)) }
        }
        DropdownMenu(menu, { menu = false }) {
            Text("${w.appName} — ${w.title}".take(60), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            DropdownMenuItem({ Text(stringResource(R.string.window_close)) }, onClick = { c.windowCommand(w.id, WindowCommand.CLOSE); menu = false }, leadingIcon = { Dot(Color(0xFFFF5F57)) })
            DropdownMenuItem({ Text(stringResource(R.string.window_minimize)) }, onClick = { c.windowCommand(w.id, WindowCommand.MINIMIZE); menu = false }, leadingIcon = { Dot(Color(0xFFFEBC2E)) })
            DropdownMenuItem({ Text(stringResource(R.string.window_full_screen)) }, onClick = { c.windowCommand(w.id, WindowCommand.FULL_SCREEN); menu = false }, leadingIcon = { Dot(Color(0xFF28C840)) })
            HorizontalDivider()
            DropdownMenuItem({ Text(stringResource(R.string.move_app_left, w.appName)) }, onClick = { c.moveApp(w, -1); menu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) })
            DropdownMenuItem({ Text(stringResource(R.string.move_app_right, w.appName)) }, onClick = { c.moveApp(w, 1); menu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
        }
    }
}

/** Tells [onMenu] while a menu is open, so the bar it hangs from stays put meanwhile. */
@Composable
private fun MenuOpen(open: Boolean, onMenu: (Boolean) -> Unit) {
    if (open) DisposableEffect(Unit) { onMenu(true); onDispose { onMenu(false) } }
}

/** What is left of the bar while it is away: a tab at the top edge that brings it back. */
@Composable
private fun BarHandle(modifier: Modifier, onShow: () -> Unit) {
    Box(
        modifier.size(width = 72.dp, height = 40.dp).clickable(onClickLabel = stringResource(R.string.show_bar), onClick = onShow),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 24.dp)
                .background(Glass, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.show_bar), Modifier.size(20.dp), tint = Ink) }
    }
}

/** How far the picture is zoomed in; a tap puts it back to fitted. */
@Composable
private fun ZoomChip(scale: Float, modifier: Modifier, onReset: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Glass).border(1.dp, Hairline, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = stringResource(R.string.fit_picture), onClick = onReset)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${(scale * 10).roundToInt() / 10.0}×", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.ZoomOutMap, null, Modifier.size(16.dp), tint = Ink)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))

/** esc, tab, the four latching modifiers, and the arrows: the keys a phone keyboard lacks. */
@Composable
private fun KeyRow(c: Controller, latch: ModifierLatch, latched: Long, modifier: Modifier) {
    Row(
        modifier.background(BarColor).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        @Composable
        fun cap(label: String, lit: Boolean = false, weight: Float = 1f, size: Int = 16, onClick: () -> Unit) = Box(
            Modifier.weight(weight).height(40.dp).clip(RoundedCornerShape(10.dp))
                .background(if (lit) Accent else ChipColor)
                .combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = if (lit) Color.White else Ink, fontSize = size.sp, fontWeight = FontWeight.Medium) }

        fun tap(usage: Int) = c.chord(usage, latch.take())
        cap("esc", weight = 1.2f, size = 13) { tap(HidKeys.ESCAPE) }
        cap("⇥") { tap(HidKeys.TAB) }
        cap("⌃", latched and Modifiers.CONTROL != 0L) { latch.toggle(Modifiers.CONTROL) }
        cap("⌥", latched and Modifiers.OPTION != 0L) { latch.toggle(Modifiers.OPTION) }
        cap("⌘", latched and Modifiers.COMMAND != 0L) { latch.toggle(Modifiers.COMMAND) }
        cap("⇧", latched and Modifiers.SHIFT != 0L) { latch.toggle(Modifiers.SHIFT) }
        cap("←") { tap(HidKeys.LEFT) }
        cap("↓") { tap(HidKeys.DOWN) }
        cap("↑") { tap(HidKeys.UP) }
        cap("→") { tap(HidKeys.RIGHT) }
    }
}

@Composable
private fun Trackpad(
    c: Controller,
    latch: ModifierLatch,
    scope: kotlinx.coroutines.CoroutineScope,
    cursor: androidx.compose.runtime.MutableState<Pair<Double, Double>>,
    modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val macPointer by c.macPointer.collectAsState()
    // The pad starts from wherever the Mac's pointer last was.
    LaunchedEffect(macPointer) { macPointer?.let { cursor.value = it } }
    Box(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF16181D)).border(1.dp, Hairline, RoundedCornerShape(20.dp))
            .onSizeChanged { size = it }
            .trackpadInput(c, { size.width.toFloat().coerceAtLeast(1f) to (size.width / 1.6f).coerceAtLeast(1f) }, cursor, latch, scope),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.TouchApp, null, Modifier.size(26.dp), tint = InkDim.copy(alpha = 0.6f))
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.trackpad), color = InkDim, fontSize = 13.sp)
        }
    }
}

/**
 * The software keyboard. Its field never shows: each edit is diffed against what it held, the
 * deletions sent as Delete and the new text as typed text (or, with ⌃⌥⌘ latched, as shortcuts).
 */
@Composable
private fun HiddenKeyboard(c: Controller, latch: ModifierLatch, up: Boolean, onClosed: () -> Unit) {
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val seed = "  "
    var value by remember { mutableStateOf(TextFieldValue(seed, TextRange(seed.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(up) {
        if (up) { runCatching { focus.requestFocus() }; kb?.show() } else kb?.hide()
    }
    if (!up) return
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value.text
            val now = new.text
            var p = 0
            while (p < old.length && p < now.length && old[p] == now[p]) p++
            val deleted = old.length - p
            val inserted = now.substring(p)
            repeat(deleted) { c.chord(HidKeys.BACKSPACE, 0) }
            if (inserted.isNotEmpty()) {
                val mods = latch.value
                if (mods and (Modifiers.CONTROL or Modifiers.OPTION or Modifiers.COMMAND) != 0L) {
                    val m = latch.take()
                    inserted.forEach { ch ->
                        HidKeys.forChar(ch)?.let { (u, shift) -> c.chord(u, m or (if (shift) Modifiers.SHIFT else 0)) }
                    }
                } else {
                    if (mods and Modifiers.SHIFT != 0L) latch.take()
                    c.text(if (mods and Modifiers.SHIFT != 0L) inserted.uppercase() else inserted)
                }
            }
            // Keep the field short; never cut into an input method's composition.
            value = if (new.composition == null && (now.length > 64 || now.length < seed.length)) TextFieldValue(seed, TextRange(seed.length)) else new
        },
        modifier = Modifier.size(1.dp).focusRequester(focus).onFocusChanged { st ->
            if (hadFocus && !st.isFocused) onClosed()
            hadFocus = st.isFocused
        },
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
    )
}

/** "Paste on ‹Mac›": shown when this device has new text on its clipboard while connected. */
@Composable
private fun ClipboardChip(c: Controller, modifier: Modifier) {
    val note by c.clipboardNote.collectAsState()
    val windows by c.windows.collectAsState()
    var offer by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle, windows?.clipboard) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && c.macTakesClipboard) {
                c.localClipboardText()?.let { offer = it; shownAt = System.currentTimeMillis() }
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(shownAt) { if (shownAt > 0) { kotlinx.coroutines.delay(20_000); offer = null } }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(4_000); c.clearClipboardNote() } }
    val mac = windows?.macName ?: "Mac"
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        offer?.let { text ->
            Row(
                Modifier.background(Color(0xEE262A33), RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.paste_on, mac), color = Color.White)
                TextButton({ c.pasteOnMac(text); offer = null }) { Text(stringResource(R.string.paste)) }
                TextButton({ offer = null }) { Text(stringResource(R.string.dismiss), color = Color.White.copy(alpha = 0.7f)) }
            }
        }
        note?.let {
            Text(it, Modifier.padding(top = 6.dp).background(Color(0xEE262A33), RoundedCornerShape(10.dp)).padding(10.dp), color = Color.White)
        }
    }
}
