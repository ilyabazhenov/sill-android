package dev.remotemac.app.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remotemac.app.MainActivity
import dev.remotemac.app.stream.Controller
import dev.remotemac.protocol.HostSettingsChange
import dev.remotemac.protocol.MacMenuItem
import dev.remotemac.protocol.QualityPreset
import dev.remotemac.protocol.StreamSource

/** The Mac's streaming settings, as its own menu has them, and this device's. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(c: Controller, showStats: MutableState<Boolean>, onDismiss: () -> Unit) {
    val state by c.settings.collectAsState()
    val pending by c.settingsPending.collectAsState()
    val windows by c.windows.collectAsState()
    val route by c.route.collectAsState()
    val share by c.shareClipboard.collectAsState()
    val gestures by c.gesturesOn.collectAsState()
    val trackpadMode by c.trackpadMode.collectAsState()
    val autoKeyboard by c.autoKeyboard.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text(windows?.macName ?: "Mac", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val st = state
            if (st == null) {
                Text("Update Sill on the Mac to change its settings from here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val s = st.settings.let { base ->
                    val p = pending ?: return@let base
                    base.copy(
                        maxFPS = p.maxFPS ?: base.maxFPS, bitrate = p.bitrate ?: base.bitrate,
                        captureScale = p.captureScale ?: base.captureScale, prioritizeSpeed = p.prioritizeSpeed ?: base.prioritizeSpeed,
                        virtualDisplay = p.virtualDisplay ?: base.virtualDisplay, directWireless = p.directWireless ?: base.directWireless,
                    )
                }
                val running = st.stream?.let { "${it.width}×${it.height} · ${it.fps} fps · ${it.mbps} Mbps" + if (it.onVirtualDisplay) " · on the virtual display" else "" }
                Text(listOfNotNull(running ?: "Nothing streaming", route).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                st.away?.let { a ->
                    Text(
                        if (a.awayRunning) "Away: ${QualityPreset.name(a.awayBitrate)} · ${if (a.awayCaptureScale >= 2) "Retina" else "Standard"}"
                        else "Home quality: ${QualityPreset.name(a.homeBitrate)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
                st.link?.takeIf { it.isBehind }?.let { l ->
                    Spacer(Modifier.height(8.dp))
                    Text("The link can’t carry ${QualityPreset.name(l.bitrate)}.", color = MaterialTheme.colorScheme.error)
                    l.suggestedBitrate?.let { b ->
                        Button({ c.changeSettings(HostSettingsChange(bitrate = b, captureScale = l.suggestedCaptureScale)) }) { Text("Use ${QualityPreset.name(b)}") }
                    }
                }
                if (st.softwareEncoder) Text(
                    "The Mac’s hardware encoder is busy, so for now streams run at up to 60 fps at Standard.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )

                Section("Quality")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QualityPreset.entries.forEach { q ->
                        FilterChip(s.bitrate == q.bitrate, { c.changeSettings(HostSettingsChange(bitrate = q.bitrate)) }, label = { Text("${q.title} · ${q.bitrate / 1_000_000}") })
                    }
                }
                Text("Ultra and Extreme need very fast Wi‑Fi; if the picture lags, step down.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("Resolution")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(s.captureScale >= 2, { c.changeSettings(HostSettingsChange(captureScale = 2.0)) }, label = { Text("Retina") })
                    FilterChip(s.captureScale < 2, { c.changeSettings(HostSettingsChange(captureScale = 1.0)) }, label = { Text("Standard") })
                }
                Section("Frame rate limit")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 120).forEach { f -> FilterChip(s.maxFPS == f, { c.changeSettings(HostSettingsChange(maxFPS = f)) }, label = { Text("$f fps") }) }
                }
                ToggleRow("Prioritize encoding speed", s.prioritizeSpeed) { c.changeSettings(HostSettingsChange(prioritizeSpeed = it)) }
                if (st.virtualDisplayAvailable) {
                    ToggleRow("Virtual display", s.virtualDisplay, "Each window streams from its own display, so it keeps updating behind other windows.") {
                        c.changeSettings(HostSettingsChange(virtualDisplay = it))
                    }
                }
                st.virtualDisplayNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s.directWireless?.let { dw ->
                    ToggleRow("Direct Wireless Connection", dw, "For iPhone and iPad only; Android connects over your network.") {
                        c.changeSettings(HostSettingsChange(directWireless = it))
                    }
                }
                if (!st.persistent) Text("This Mac keeps changes until Sill quits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("This device")
            if ((windows?.clipboard ?: 0) >= 1) ToggleRow("Share clipboard", share, "Text copied on the Mac arrives here; Paste on Mac sends this device’s.") { c.setShareClipboard(it) }
            if ((windows?.gestures ?: 0) >= 1) ToggleRow("Three-finger gestures", gestures, "Swipe up: Mission Control · down: App Exposé · left/right: Spaces · pinch: Apps · spread: Desktop.") { c.setGestures(it) }
            ToggleRow("Picture as trackpad", trackpadMode, "Held sideways, drag anywhere on the picture to move the Mac’s pointer; tap to click. Off: touch clicks right where you tap.") { c.setTrackpadMode(it) }
            ToggleRow("Keyboard for text fields", autoKeyboard, "A click into text on the Mac (where its pointer turns into an I-beam) brings up the keyboard.") { c.setAutoKeyboard(it) }
            ToggleRow("Show link statistics", showStats.value) { showStats.value = it }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onDismiss(); c.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            ) { Text("Disconnect") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(t: String) {
    Spacer(Modifier.height(16.dp))
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ToggleRow(title: String, on: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
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
    var query by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        activity.textEntryActive = true
        onDispose { activity.textEntryActive = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search apps and windows") }, singleLine = true)
            val q = query.trim().lowercase()
            val open = (windows?.windows ?: emptyList()).filter { q.isEmpty() || it.title.lowercase().contains(q) || it.appName.lowercase().contains(q) }
            val all = apps.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            LazyColumn(Modifier.fillMaxWidth().height(520.dp)) {
                if (open.isNotEmpty()) item { Section("Open windows") }
                items(open, key = { "w" + it.id }) { w ->
                    ListRow(icons[w.bundleID], w.title.ifEmpty { w.appName }, w.appName) { c.select(StreamSource.Window(w.id)); onDismiss() }
                }
                if (all.isNotEmpty()) item { Section("All apps") }
                items(all, key = { "a" + it.bundleID }) { a ->
                    ListRow(icons[a.bundleID], a.name, null) { c.launchApp(a.bundleID); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.ImageBitmap?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon, null, Modifier.size(32.dp)) else Box(Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, maxLines = 1)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) IconButton({ stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(stack.lastOrNull()?.title ?: (menus.app ?: "Menus"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            }
            menus.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            val current = stack.lastOrNull()
            val items = if (current == null) menus.top else menus.items[current.id]
            if (items == null) {
                Text("Reading the menu…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(520.dp)) {
                    items(items) { item ->
                        if (item.separator == true) { HorizontalDivider(Modifier.padding(vertical = 4.dp)); return@items }
                        val enabled = item.enabled != false && !menus.stale
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = enabled) {
                                    if (item.submenu == true || current == null) { stack.add(item); c.fetchMenu(item) }
                                    else { c.pressMenu(item); onDismiss() }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.mark ?: "", Modifier.width(20.dp))
                            Text(item.title ?: "", Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            item.key?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (item.submenu == true || current == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                }
            }
        }
    }
}
