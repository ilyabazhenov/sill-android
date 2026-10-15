package dev.remotemac.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.remotemac.app.data.SavedMac
import dev.remotemac.app.net.FoundMac
import dev.remotemac.app.stream.Controller
import dev.remotemac.app.stream.Phase
import dev.remotemac.protocol.HomeDoor
import dev.remotemac.protocol.ParsedAddress

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(controller: Controller, onScan: () -> Unit) {
    val found by controller.discovery.macs.collectAsState()
    val savedMacs by controller.saved.all.collectAsState()
    val status by controller.status.collectAsState()
    val phase by controller.phase.collectAsState()
    val browseFailed by controller.discovery.failed.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<SavedMac?>(null) }

    val listedIDs = found.mapNotNull { r -> savedMacs.firstOrNull { it.recognizes(r.tag) }?.macID }.toSet()
    val away = savedMacs.filter { it.macID !in listedIDs && !it.revoked }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Spacer(Modifier.height(32.dp))
                    Text("Remote Mac", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your Mac’s windows on this device. Needs the free Sill app on the Mac (getsill.app).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("On this network")
                }
                if (found.isEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                            if (!browseFailed) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(
                                if (browseFailed) "Can’t look for Macs on this network. Check Wi‑Fi." else "Looking for Macs with Sill open…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(found, key = { "row:" + it.serviceName }) { row ->
                    val mac = savedMacs.firstOrNull { it.recognizes(row.tag) && !it.revoked }
                    MacRow(
                        title = mac?.name ?: row.serviceName,
                        word = rowWord(row, mac),
                        remote = false,
                        busy = phase == Phase.DIALING && controller.target.value?.name == (mac?.name ?: row.serviceName),
                        onClick = { controller.tap(row) },
                        onLong = { mac?.let { forget = it } },
                    )
                }
                if (away.isNotEmpty()) {
                    item { Spacer(Modifier.height(12.dp)); SectionTitle("Away from home") }
                    items(away, key = { "saved:" + it.macID }) { mac ->
                        MacRow(
                            title = mac.name,
                            word = "Remote",
                            remote = true,
                            busy = phase == Phase.DIALING && controller.target.value?.name == mac.name,
                            onClick = { controller.connectRemote(mac) },
                            onLong = { forget = mac },
                        )
                    }
                }
                item {
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = onScan) {
                            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan Pairing Code")
                        }
                        OutlinedButton(onClick = { addOpen = true }) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.size(8.dp)); Text("Add a Mac…")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "On the Mac: open Sill, then tap the Mac here. The first time, the Mac shows a code to scan or type. " +
                            "Away from home, turn on Remote Access in Sill’s settings and use a VPN such as Tailscale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }

    if (addOpen) AddMacDialog(controller, onDismiss = { addOpen = false })
    forget?.let { mac ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Forget ${mac.name}?") },
            text = { Text("This device will have to pair with it again. Remove this device on the Mac too (Sill › Settings › Devices).") },
            confirmButton = { TextButton({ controller.saved.forget(mac.macID); forget = null }) { Text("Forget") } },
            dismissButton = { TextButton({ forget = null }) { Text("Cancel") } },
        )
    }
}

private fun rowWord(row: FoundMac, mac: SavedMac?): String = when {
    row.door == HomeDoor.PLAIN -> "Not encrypted"
    mac != null -> "Wi‑Fi"
    else -> "Not paired"
}

@Composable
private fun SectionTitle(t: String) =
    Text(t.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacRow(title: String, word: String, remote: Boolean, busy: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (remote) Icons.Default.Cloud else Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Text(word, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pair from afar (the Mac's address and its code) or, for development, connect by address. */
@Composable
private fun AddMacDialog(controller: Controller, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var plain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a Mac") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "On the Mac choose Pair iPhone or iPad… in Sill’s menu. Type the address its window shows " +
                        "(a Tailscale name works from anywhere), then its code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    address, { address = it.trim() },
                    label = { Text("Address") },
                    placeholder = { Text("mac-mini.tail1234.ts.net") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(plain, { plain = it })
                    Spacer(Modifier.size(8.dp))
                    Text("Unencrypted test host (SillHost without --pairing)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ParsedAddress.parse(address) != null, onClick = {
                val a = ParsedAddress.parse(address)!!
                if (plain) controller.connectAddress(a.host, a.port ?: 7455, plain = true)
                else controller.startRemotePairing(address)
                onDismiss()
            }) { Text(if (plain) "Connect" else "Next") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
