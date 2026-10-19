package dev.sash.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.sash.app.MainActivity
import dev.sash.app.R
import dev.sash.app.stream.Controller
import dev.sash.app.stream.Phase

private val Accent = Color(0xFF3A7BFF)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Accent, secondary = Accent) else lightColorScheme(primary = Accent, secondary = Accent),
        content = content,
    )
}

@Composable
fun AppRoot(controller: Controller, activity: MainActivity) {
    AppTheme {
        val locked by activity.lock.locked.collectAsState()
        if (locked) LockScreen(onUnlock = { activity.promptUnlock() }) else AppContent(controller, activity)
    }
}

@Composable
private fun AppContent(controller: Controller, activity: MainActivity) {
    run {
        val phase by controller.phase.collectAsState()
        val windows by controller.windows.collectAsState()
        var scanning by remember { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { controller.notices.collect { snack.showSnackbar(it) } }

        val streaming = (phase == Phase.CONNECTED || phase == Phase.RECONNECTING) && windows != null
        LaunchedEffect(streaming) { activity.streaming(streaming) }

        Box(Modifier.fillMaxSize()) {
            when {
                scanning -> QrScannerScreen(
                    onLink = { scanning = false; controller.pairLink(it) },
                    onClose = { scanning = false },
                )
                streaming -> StreamScreen(controller, activity)
                else -> ConnectScreen(controller, activity.lock, onScan = { scanning = true })
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }

        val prompt by controller.pairing.collectAsState()
        if (!scanning) prompt?.let { p -> PairingDialog(p, controller, onScan = { scanning = true }, activity = activity) }

        val link by activity.pendingLink
        link?.let { l ->
            AlertDialog(
                onDismissRequest = { activity.pendingLink.value = null },
                title = { Text(stringResource(R.string.pair_link_title, l.name)) },
                text = { Text(stringResource(R.string.pair_link_body, l.name)) },
                confirmButton = { TextButton({ activity.pendingLink.value = null; controller.pairLink(l) }) { Text(stringResource(R.string.pair)) } },
                dismissButton = { TextButton({ activity.pendingLink.value = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** Over everything while the app is locked: nothing of the Mac shows, and no touch reaches it. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.locked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
        }
    }
}
