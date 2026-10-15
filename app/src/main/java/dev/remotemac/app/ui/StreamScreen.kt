package dev.remotemac.app.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.remotemac.app.MainActivity
import dev.remotemac.app.stream.Controller
import dev.remotemac.app.stream.HidKeys
import dev.remotemac.app.stream.Phase
import dev.remotemac.app.stream.Target
import dev.remotemac.protocol.Modifiers
import dev.remotemac.protocol.StreamSource
import dev.remotemac.protocol.Viewport
import dev.remotemac.protocol.WindowCommand
import dev.remotemac.protocol.WindowInfo
import kotlin.math.roundToInt

private val BarColor = Color(0xFF15171C)
private val ChipColor = Color(0xFF262A33)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamScreen(c: Controller, activity: MainActivity) {
    val view = LocalView.current
    LaunchedEffect(Unit) { hapticLong = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }

    val windows by c.windows.collectAsState()
    val phase by c.phase.collectAsState()
    val status by c.status.collectAsState()
    val videoSize by c.videoSize.collectAsState()
    val scope = rememberCoroutineScope()
    val latchState = remember { mutableStateOf(0L) }
    val latch = remember { ModifierLatch(latchState) }
    var keyboardUp by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var scale by remember { mutableStateOf<Double?>(null) }
    val trackCursor = remember { mutableStateOf(0.5 to 0.5) }
    val showStats = remember { mutableStateOf(false) }
    val trackpadMode by c.trackpadMode.collectAsState()
    /** Window y where the key row over the keyboard begins: the picture is lifted to clear it. */
    var keysTop by remember { mutableStateOf<Float?>(null) }

    // A click into text brings up the keyboard, unless a hardware one is there to type on.
    val config = LocalConfiguration.current
    val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    LaunchedEffect(hardKeyboard) { if (!hardKeyboard) c.clickedIntoText.collect { keyboardUp = true } }
    // The keyboard put away by the system (Back, its own hide key) is closed here too.
    val imeUp = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) { if (imeUp) imeSeen = true else if (imeSeen) { imeSeen = false; keyboardUp = false } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxHeight > maxWidth && maxWidth < 600.dp
        val content: @Composable () -> Unit = {
            if (portrait) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                        VideoPane(c, latch, scope, scale, trackCursor.value, showStats.value, null)
                    }
                    ButtonRow(c, keyboardUp, onKeyboard = { keyboardUp = !keyboardUp }, onSheet = { sheet = it }, scale = scale, onScale = { scale = it })
                    WindowStrip(c, Modifier.fillMaxWidth().height(64.dp))
                    KeyRow(c, latch, latchState.value, Modifier.fillMaxWidth())
                    Trackpad(c, latch, scope, trackCursor, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
                    if (keyboardUp) Spacer(Modifier.height(1.dp))
                }
            } else {
                // The picture keeps its room with the keyboard up (the Mac sizes its windows to that
                // room); it is lifted instead. The bar and the key row come after it so they draw over
                // the part lifted out of its room.
                val barInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.fillMaxWidth().windowInsetsPadding(barInsets).height(64.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            VideoPane(
                                c, latch, scope, scale, if (trackpadMode) trackCursor.value else null, showStats.value,
                                trackCursor.takeIf { trackpadMode }, visibleBottom = if (keyboardUp) keysTop else null,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(BarColor).windowInsetsPadding(barInsets).height(64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BarButton(Icons.Default.Apps, "Apps") { sheet = Sheet.APPS }
                        WindowStrip(c, Modifier.weight(1f).fillMaxHeight())
                        MenusButton(c) { sheet = Sheet.MENUS }
                        ScaleButton(scale) { scale = it }
                        BarButton(Icons.Default.Mouse, if (trackpadMode) "Trackpad: on" else "Trackpad: off", lit = trackpadMode) { c.setTrackpadMode(!trackpadMode) }
                        BarButton(Icons.Default.Keyboard, "Keyboard", lit = keyboardUp) { keyboardUp = !keyboardUp }
                        BarButton(Icons.Default.DesktopMac, "Desktop", lit = windows?.active == StreamSource.Desktop) { c.select(StreamSource.Desktop) }
                        BarButton(Icons.Default.Settings, "Settings") { sheet = Sheet.SETTINGS }
                    }
                    if (keyboardUp) {
                        KeyRow(
                            c, latch, latchState.value,
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .onGloballyPositioned { keysTop = it.positionInWindow().y },
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
            ) { Text(status ?: "Reconnecting…", color = Color.White) }
        }
        ClipboardChip(c, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp))
    }

    // Keep the viewport the Mac sizes windows to in step with the picture's room and the Aa scale.
    when (sheet) {
        Sheet.SETTINGS -> SettingsSheet(c, showStats, onDismiss = { sheet = null })
        Sheet.APPS -> AppsSheet(c, activity, onDismiss = { sheet = null })
        Sheet.MENUS -> MenusSheet(c, onDismiss = { sheet = null })
        null -> {}
    }
}

enum class Sheet { SETTINGS, APPS, MENUS }

/**
 * The Mac's picture, letterboxed, with the Mac's pointer drawn over it. Touch on it is direct, or,
 * given a [trackpad] cursor, relative: the picture works as a trackpad moving that cursor. Given a
 * [visibleBottom] (window y) inside it, the picture is lifted to keep the last click above it.
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
    visibleBottom: Float? = null,
) {
    val videoSize by c.videoSize.collectAsState()
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

    // Lifted so the last click (the text field being typed into) sits in sight above the keys: moved
    // only as far as that needs, and never so far that the picture's bottom edge clears the keys.
    val lift = remember { mutableFloatStateOf(0f) }
    val wantLift = run {
        val bottom = (visibleBottom ?: return@run 0f) - boxTop
        if (bottom >= box.height) return@run 0f
        val at = lastClick ?: macPointer ?: (0.5 to 0.5)
        val y = fit.top + at.second.toFloat() * fit.height
        val margin = with(density) { 24.dp.toPx() }
        val lo = y - (bottom - maxOf(2 * margin, bottom * 0.35f))
        val hi = y - margin
        val l = if (lo >= hi) lo else lift.floatValue.coerceIn(lo, hi)
        l.coerceIn(0f, maxOf(0f, fit.top + fit.height - bottom))
    }
    SideEffect { lift.floatValue = wantLift }
    val lifted by animateFloatAsState(wantLift, label = "lift")
    val rect = if (lifted == 0f) fit else fit.copy(top = fit.top - lifted)
    val rectRef = remember { mutableStateOf<VideoRect?>(null) }
    rectRef.value = if (box.width > 0) rect else null

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
        val w = with(density) { rect.width.toDp() }
        val h = with(density) { rect.height.toDp() }
        AndroidView(
            modifier = Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }.size(w, h),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { c.decoder.attach(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { c.decoder.attach(null) }
                    })
                }
            },
        )
        key(trackpad != null) {
            if (trackpad != null) {
                // The pointer carries on from wherever the Mac's pointer last was.
                LaunchedEffect(macPointer) { macPointer?.let { trackpad.value = it } }
                Box(
                    Modifier.fillMaxSize()
                        .trackpadInput(c, { rectRef.value?.let { it.width to it.height } ?: (1f to 1f) }, trackpad, latch, scope) { rectRef.value }
                        .hoverInput(c) { rectRef.value },
                )
            } else {
                Box(Modifier.fillMaxSize().directInput(c, { rectRef.value }, latch, scope))
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
    }
}

@Composable
private fun StatsLine(c: Controller, modifier: Modifier) {
    val s by c.stats.collectAsState()
    val route by c.route.collectAsState()
    Text(
        "${s.fps} fps · ${if (s.frameAgeMs < 0) "–" else "${s.frameAgeMs} ms"} · rtt ${if (s.rttMs < 0) "–" else "${s.rttMs} ms"}${route?.let { " · $it" } ?: ""}",
        modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White, fontSize = 12.sp,
    )
}

@Composable
private fun BarButton(icon: ImageVector, label: String, lit: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).size(46.dp).clip(RoundedCornerShape(12.dp))
            .background(if (lit) MaterialTheme.colorScheme.primary else ChipColor)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun MenusButton(c: Controller, onOpen: () -> Unit) {
    val menus by c.menus.collectAsState()
    if (menus.top.isEmpty()) return
    BarButton(Icons.Default.Menu, "Menus of ${menus.app ?: "the app"}", onClick = onOpen)
}

/** Aa: how large the Mac's text renders here. Off leaves the Mac's windows their own size. */
@Composable
private fun ScaleButton(scale: Double?, onScale: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.padding(horizontal = 3.dp).size(46.dp).clip(RoundedCornerShape(12.dp))
                .background(if (scale != null) MaterialTheme.colorScheme.primary else ChipColor)
                .combinedClickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.TextFields, "Text size", tint = Color.White) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Leave Mac windows as they are") }, onClick = { onScale(null); open = false })
            listOf(0.5, 0.75, 1.0, 1.25, 1.5).forEach { s ->
                DropdownMenuItem(
                    { Text("${s}× ${if (s == 1.0) "(fit window to this screen)" else ""}") },
                    onClick = { onScale(s); open = false },
                    trailingIcon = { if (scale == s) Text("✓") },
                )
            }
        }
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
        BarButton(Icons.Default.Apps, "Apps") { onSheet(Sheet.APPS) }
        ScaleButton(scale, onScale)
        BarButton(Icons.Default.Keyboard, "Keyboard", lit = keyboardUp, onClick = onKeyboard)
        BarButton(Icons.Default.DesktopMac, "Desktop", lit = windows?.active == StreamSource.Desktop) { c.select(StreamSource.Desktop) }
        MenusButton(c) { onSheet(Sheet.MENUS) }
        BarButton(Icons.Default.Settings, "Settings") { onSheet(Sheet.SETTINGS) }
    }
}

/** Live thumbnails of the Mac's windows. Tap to stream one; hold for its traffic lights. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowStrip(c: Controller, modifier: Modifier) {
    val windows by c.windows.collectAsState()
    val thumbs by c.thumbnails.collectAsState()
    val icons by c.icons.collectAsState()
    val list = windows?.windows ?: emptyList()
    val active = (windows?.active as? StreamSource.Window)?.id
    LazyRow(
        modifier.background(BarColor).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(list, key = { it.id }) { w ->
            WindowThumb(c, w, thumbs[w.id], icons[w.bundleID], w.id == active)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowThumb(c: Controller, w: WindowInfo, thumb: ImageBitmap?, icon: ImageBitmap?, active: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box(
        Modifier.height(50.dp).width(78.dp).clip(RoundedCornerShape(8.dp))
            .background(ChipColor)
            .border(if (active) 2.dp else 0.dp, if (active) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { c.select(StreamSource.Window(w.id)) },
                onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); menu = true },
            ),
    ) {
        if (thumb != null) Image(thumb, w.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(w.appName, Modifier.align(Alignment.Center).padding(4.dp), color = Color.White, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (icon != null) Image(icon, null, Modifier.align(Alignment.BottomEnd).padding(2.dp).size(18.dp))
        DropdownMenu(menu, { menu = false }) {
            Text("${w.appName} — ${w.title}".take(60), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            DropdownMenuItem({ Text("Close") }, onClick = { c.windowCommand(w.id, WindowCommand.CLOSE); menu = false }, leadingIcon = { Dot(Color(0xFFFF5F57)) })
            DropdownMenuItem({ Text("Minimize") }, onClick = { c.windowCommand(w.id, WindowCommand.MINIMIZE); menu = false }, leadingIcon = { Dot(Color(0xFFFEBC2E)) })
            DropdownMenuItem({ Text("Full Screen") }, onClick = { c.windowCommand(w.id, WindowCommand.FULL_SCREEN); menu = false }, leadingIcon = { Dot(Color(0xFF28C840)) })
        }
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(12.dp).background(color, RoundedCornerShape(6.dp)))

/** esc, tab, the four latching modifiers, and the arrows: the keys a phone keyboard lacks. */
@Composable
private fun KeyRow(c: Controller, latch: ModifierLatch, latched: Long, modifier: Modifier) {
    Row(
        modifier.background(BarColor).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun cap(label: String, lit: Boolean = false, weight: Float = 1f, onClick: () -> Unit) = Box(
            Modifier.weight(weight).height(40.dp).clip(RoundedCornerShape(8.dp))
                .background(if (lit) MaterialTheme.colorScheme.primary else ChipColor)
                .combinedClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

        fun tap(usage: Int) = c.chord(usage, latch.take())
        cap("esc") { tap(HidKeys.ESCAPE) }
        cap("tab") { tap(HidKeys.TAB) }
        cap("ctrl", latched and Modifiers.CONTROL != 0L) { latch.toggle(Modifiers.CONTROL) }
        cap("opt", latched and Modifiers.OPTION != 0L) { latch.toggle(Modifiers.OPTION) }
        cap("cmd", latched and Modifiers.COMMAND != 0L) { latch.toggle(Modifiers.COMMAND) }
        cap("shift", latched and Modifiers.SHIFT != 0L) { latch.toggle(Modifiers.SHIFT) }
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
        modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E2128))
            .onSizeChanged { size = it }
            .trackpadInput(c, { size.width.toFloat().coerceAtLeast(1f) to (size.width / 1.6f).coerceAtLeast(1f) }, cursor, latch, scope),
        contentAlignment = Alignment.Center,
    ) {
        Text("Trackpad", color = Color.White.copy(alpha = 0.25f), fontSize = 13.sp)
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
                Text("Paste on $mac?", color = Color.White)
                TextButton({ c.pasteOnMac(text); offer = null }) { Text("Paste") }
                TextButton({ offer = null }) { Text("Dismiss", color = Color.White.copy(alpha = 0.7f)) }
            }
        }
        note?.let {
            Text(it, Modifier.padding(top = 6.dp).background(Color(0xEE262A33), RoundedCornerShape(10.dp)).padding(10.dp), color = Color.White)
        }
    }
}
