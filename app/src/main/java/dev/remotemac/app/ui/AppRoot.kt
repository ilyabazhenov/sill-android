package dev.remotemac.app.ui

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.remotemac.app.MainActivity
import dev.remotemac.app.stream.Controller
import dev.remotemac.app.stream.Phase

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
                else -> ConnectScreen(controller, onScan = { scanning = true })
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }

        val prompt by controller.pairing.collectAsState()
        if (!scanning) prompt?.let { p -> PairingDialog(p, controller, onScan = { scanning = true }, activity = activity) }

        val link by activity.pendingLink
        link?.let { l ->
            AlertDialog(
                onDismissRequest = { activity.pendingLink.value = null },
                title = { Text("Pair with ${l.name}?") },
                text = { Text("This device will be able to see and control ${l.name}. Pair only with a Mac you own.") },
                confirmButton = { TextButton({ activity.pendingLink.value = null; controller.pairLink(l) }) { Text("Pair") } },
                dismissButton = { TextButton({ activity.pendingLink.value = null }) { Text("Cancel") } },
            )
        }
    }
}
