package dev.remotemac.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.remotemac.app.MainActivity
import dev.remotemac.app.stream.Controller
import dev.remotemac.app.stream.PairingPrompt
import dev.remotemac.protocol.PairLink
import java.util.concurrent.Executors

@Composable
fun PairingDialog(p: PairingPrompt, controller: Controller, onScan: () -> Unit, activity: MainActivity) {
    var code by remember(p.macName) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        activity.textEntryActive = true
        onDispose { activity.textEntryActive = false }
    }
    AlertDialog(
        onDismissRequest = { controller.cancelPairing() },
        title = { Text(if (p.codeShown) "Enter the code from ${p.macName}" else "Pairing with ${p.macName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!p.codeShown) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Asking ${p.macName} to show its code…")
                    }
                } else {
                    Text(
                        "${p.macName} shows a QR code and a 12-digit code. Scan the QR code, or type the digits.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(12) },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = true,
                        enabled = !p.busy,
                        visualTransformation = CodeGrouping,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 22.sp, textAlign = TextAlign.Center, letterSpacing = 2.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        keyboardActions = KeyboardActions(onDone = { controller.submitCode(code) }),
                        placeholder = { Text("0000 0000 0000", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    )
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    if (p.busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Checking…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            if (p.codeShown) TextButton(enabled = code.length == 12 && !p.busy, onClick = { controller.submitCode(code) }) { Text("Pair") }
        },
        dismissButton = {
            Row {
                if (p.codeShown) TextButton({ onScan() }) { Text("Scan QR") }
                TextButton({ controller.cancelPairing() }) { Text("Cancel") }
            }
        },
    )
}

/** "482913557208" shown as "4829 1355 7208". */
private val CodeGrouping = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val out = raw.chunked(4).joinToString(" ")
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(out),
        object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + (if (offset == 0) 0 else (offset - 1) / 4)
            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, raw.length)
        },
    )
}

/** The camera, looking for the Mac's `sill://pair` QR code. */
@Composable
fun QrScannerScreen(onLink: (PairLink) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hint by remember { mutableStateOf("Point the camera at the QR code on your Mac.") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraPreview(onText = { t ->
            val link = PairLink.parse(t)
            if (link != null) onLink(link) else if (t.startsWith("sill:")) hint = "That pairing link is damaged or from a newer Sill."
        })
        Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp)))
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (granted) hint else "Allow the camera to scan the Mac’s code, or type its code instead.", color = Color.White, textAlign = TextAlign.Center)
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
            Icon(Icons.Default.Close, "Close", tint = Color.White)
        }
    }
}

@Composable
private fun CameraPreview(onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (done) return@setAnalyzer
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val data = ByteArray(buf.remaining()).also { buf.get(it) }
                        val src = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))) }.getOrNull()
                        reader.reset()
                        val text = result?.text
                        if (text != null) {
                            ContextCompat.getMainExecutor(context).execute {
                                if (!done && PairLink.parse(text) != null) done = true
                                onText(text)
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
