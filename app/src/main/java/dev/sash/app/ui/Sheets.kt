package dev.sash.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sash.app.MainActivity
import dev.sash.app.R
import dev.sash.app.stream.BarPosition
import dev.sash.app.stream.Controller
import dev.sash.app.stream.orderedWindows
import dev.sash.protocol.HostSettingsChange
import dev.sash.protocol.MacMenuItem
import dev.sash.protocol.QualityPreset
import dev.sash.protocol.StreamSource

/** The Mac's streaming settings, as its own menu has them, and this device's. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(c: Controller, showStats: MutableState<Boolean>, tall: Boolean, onDismiss: () -> Unit) {
    val state by c.settings.collectAsState()
    val pending by c.settingsPending.collectAsState()
    val windows by c.windows.collectAsState()
    val route by c.route.collectAsState()
    val share by c.shareClipboard.collectAsState()
    val gestures by c.gesturesOn.collectAsState()
    val trackpadMode by c.trackpadMode.collectAsState()
    val autoKeyboard by c.autoKeyboard.collectAsState()
    val barAt by c.barPosition.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            val st = state
            // The Mac and what streams from it now.
            Card {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Laptop, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(windows?.macName ?: "Mac", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (st == null) {
                            Text(stringResource(R.string.update_sill), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            val running = st.stream?.let {
                                listOfNotNull(
                                    stringResource(R.string.stream_running, it.width, it.height, it.fps, it.mbps),
                                    if (it.onVirtualDisplay) stringResource(R.string.on_virtual_display) else null,
                                ).joinToString(" · ")
                            }
                            Text(
                                listOfNotNull(running ?: stringResource(R.string.nothing_streaming), route).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            st.away?.let { a ->
                                Text(
                                    if (a.awayRunning) stringResource(R.string.away_quality, presetName(a.awayBitrate), stringResource(if (a.awayCaptureScale >= 2) R.string.retina else R.string.standard))
                                    else stringResource(R.string.home_quality, presetName(a.homeBitrate)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                st?.link?.takeIf { it.isBehind }?.let { l ->
                    Text(stringResource(R.string.link_cant_carry, presetName(l.bitrate)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    l.suggestedBitrate?.let { b ->
                        Button(
                            { c.changeSettings(HostSettingsChange(bitrate = b, captureScale = l.suggestedCaptureScale)) },
                            Modifier.padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.use_preset, presetName(b))) }
                    }
                }
                if (st?.softwareEncoder == true) Text(
                    stringResource(R.string.software_encoder),
                    Modifier.padding(bottom = 10.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }

            if (st != null) {
                val s = st.settings.let { base ->
                    val p = pending ?: return@let base
                    base.copy(
                        maxFPS = p.maxFPS ?: base.maxFPS, bitrate = p.bitrate ?: base.bitrate,
                        captureScale = p.captureScale ?: base.captureScale, prioritizeSpeed = p.prioritizeSpeed ?: base.prioritizeSpeed,
                        virtualDisplay = p.virtualDisplay ?: base.virtualDisplay, directWireless = p.directWireless ?: base.directWireless,
                    )
                }

                Section(stringResource(R.string.section_quality))
                Card {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QualityPreset.entries.forEach { q ->
                            FilterChip(
                                s.bitrate == q.bitrate, { c.changeSettings(HostSettingsChange(bitrate = q.bitrate)) },
                                label = { Text("${stringResource(presetTitle(q))} · ${q.bitrate / 1_000_000}") },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.quality_hint), Modifier.padding(top = 4.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Section(stringResource(R.string.section_mac))
                Card {
                    Label(stringResource(R.string.section_resolution))
                    Segmented(
                        listOf(stringResource(R.string.retina) to (s.captureScale >= 2), stringResource(R.string.standard) to (s.captureScale < 2)),
                    ) { i -> c.changeSettings(HostSettingsChange(captureScale = if (i == 0) 2.0 else 1.0)) }
                    Label(stringResource(R.string.section_frame_rate))
                    Segmented(listOf(60, 120).map { f -> stringResource(R.string.fps, f) to (s.maxFPS == f) }) { i ->
                        c.changeSettings(HostSettingsChange(maxFPS = listOf(60, 120)[i]))
                    }
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(stringResource(R.string.prioritize_speed), s.prioritizeSpeed) { c.changeSettings(HostSettingsChange(prioritizeSpeed = it)) }
                    if (st.virtualDisplayAvailable) {
                        ToggleRow(stringResource(R.string.virtual_display), s.virtualDisplay, stringResource(R.string.virtual_display_note)) {
                            c.changeSettings(HostSettingsChange(virtualDisplay = it))
                        }
                    }
                    st.virtualDisplayNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    s.directWireless?.let { dw ->
                        ToggleRow(stringResource(R.string.direct_wireless), dw, stringResource(R.string.direct_wireless_note)) {
                            c.changeSettings(HostSettingsChange(directWireless = it))
                        }
                    }
                    if (!st.persistent) Text(
                        stringResource(R.string.not_persistent), Modifier.padding(bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section(stringResource(R.string.section_this_device))
            Card {
                if ((windows?.clipboard ?: 0) >= 1) ToggleRow(stringResource(R.string.share_clipboard), share, stringResource(R.string.share_clipboard_note)) { c.setShareClipboard(it) }
                if ((windows?.gestures ?: 0) >= 1) ToggleRow(stringResource(R.string.gestures), gestures, stringResource(R.string.gestures_note)) { c.setGestures(it) }
                ToggleRow(stringResource(R.string.trackpad_mode), trackpadMode, stringResource(R.string.trackpad_mode_note)) { c.setTrackpadMode(it) }
                ToggleRow(stringResource(R.string.auto_keyboard), autoKeyboard, stringResource(R.string.auto_keyboard_note)) { c.setAutoKeyboard(it) }
                ToggleRow(stringResource(R.string.show_stats), showStats.value) { showStats.value = it }
                if (tall) {
                    Label(stringResource(R.string.bar_position))
                    val places = listOf(BarPosition.TOP to R.string.bar_top, BarPosition.MIDDLE to R.string.bar_middle, BarPosition.BOTTOM to R.string.bar_bottom)
                    Segmented(places.map { (at, name) -> stringResource(name) to (barAt == at) }) { i -> c.setBarPosition(places[i].first) }
                    Text(
                        stringResource(R.string.bar_position_note), Modifier.padding(top = 6.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { onDismiss(); c.disconnect() }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.disconnect), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** A group of rows on its own rounded surface. */
@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        content = content,
    )
}

@Composable
private fun Section(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t.uppercase(), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Label(t: String) {
    Text(t, Modifier.padding(top = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyLarge)
}

/** Choices side by side, one of them on; [onPick] gets the index tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (label, on) ->
            SegmentedButton(on, { onPick(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange)
    }
}

/** The Mac's open windows and its installed apps, searchable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSheet(c: Controller, activity: MainActivity, onDismiss: () -> Unit) {
    val windows by c.windows.collectAsState()
    val apps by c.apps.collectAsState()
    val icons by c.icons.collectAsState()
    val thumbs by c.thumbnails.collectAsState()
    val appOrder by c.appOrder.collectAsState()
    var query by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        activity.textEntryActive = true
        onDispose { activity.textEntryActive = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_apps)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(14.dp),
            )
            val q = query.trim().lowercase()
            val open = orderedWindows(windows?.windows ?: emptyList(), appOrder).filter { q.isEmpty() || it.title.lowercase().contains(q) || it.appName.lowercase().contains(q) }
            val all = apps.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            // Up to 520 dp tall, less where the screen is shorter, as a phone held sideways.
            // Open windows as a grid of their pictures; all apps as a list.
            LazyVerticalGrid(
                GridCells.Adaptive(150.dp),
                Modifier.weight(1f, fill = false).fillMaxWidth().height(520.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (open.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(R.string.section_open_windows)) }
                items(open, key = { "w" + it.id }) { w ->
                    WindowTile(thumbs[w.id], icons[w.bundleID], w.title.ifEmpty { w.appName }) { c.select(StreamSource.Window(w.id)); onDismiss() }
                }
                if (all.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Section(stringResource(R.string.section_all_apps)) }
                items(all, key = { "a" + it.bundleID }, span = { GridItemSpan(maxLineSpan) }) { a ->
                    ListRow(icons[a.bundleID], a.name, null) { c.launchApp(a.bundleID); onDismiss() }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** An open window: its picture, its app's icon and its title. */
@Composable
private fun WindowTile(thumb: androidx.compose.ui.graphics.ImageBitmap?, icon: androidx.compose.ui.graphics.ImageBitmap?, title: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(bottom = 10.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.ImageBitmap?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(36.dp)) else Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** The streamed app's menu bar: each menu read from the Mac when opened, an item pressed there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenusSheet(c: Controller, onDismiss: () -> Unit) {
    val menus by c.menus.collectAsState()
    val stack = remember { mutableStateListOf<MacMenuItem>() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) IconButton({ stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(
                    stack.lastOrNull()?.title ?: (menus.app ?: stringResource(R.string.menus)),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp),
                )
            }
            menus.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            val current = stack.lastOrNull()
            val items = if (current == null) menus.top else menus.items[current.id]
            if (items == null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.reading_menu), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f, fill = false).fillMaxWidth().height(520.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    items(items) { item ->
                        if (item.separator == true) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            return@items
                        }
                        val enabled = item.enabled != false && !menus.stale
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    if (item.submenu == true || current == null) { stack.add(item); c.fetchMenu(item) }
                                    else { c.pressMenu(item); onDismiss() }
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.mark ?: "", Modifier.width(22.dp), color = MaterialTheme.colorScheme.primary)
                            Text(
                                item.title ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                            item.key?.let {
                                Text(
                                    it,
                                    Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.submenu == true || current == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A quality preset's name in this device's language, or the bitrate when it matches none. */
@Composable
private fun presetName(bitrate: Int): String =
    QualityPreset.forBitrate(bitrate)?.let { stringResource(presetTitle(it)) } ?: stringResource(R.string.mbps, bitrate / 1_000_000)

private fun presetTitle(q: QualityPreset): Int = when (q) {
    QualityPreset.LOW -> R.string.preset_low
    QualityPreset.EFFICIENT -> R.string.preset_efficient
    QualityPreset.BALANCED -> R.string.preset_balanced
    QualityPreset.HIGH -> R.string.preset_high
    QualityPreset.PRO -> R.string.preset_pro
    QualityPreset.ULTRA -> R.string.preset_ultra
    QualityPreset.EXTREME -> R.string.preset_extreme
}
