package dev.ene.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.ene.companion.pairing.QrScanGate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PairingCameraScreen(modifier: Modifier = Modifier, onQr: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = hasPermission() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PC의 연결 QR 스캔", style = MaterialTheme.typography.headlineSmall)
        Text("카메라 영상과 QR 이미지는 저장하지 않습니다.")
        if (granted) CameraPreview(Modifier.weight(1f).fillMaxWidth(), onQr)
        else {
            CameraPermissionNotice(denied, onRequest = { permission.launch(Manifest.permission.CAMERA) }, onSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            })
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("스캔 취소") }
    }
}

@Composable
fun CameraPermissionNotice(denied: Boolean, onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (denied) "카메라 권한이 거절되었습니다. 다시 허용하거나 앱 설정에서 카메라 권한을 켜 주세요." else "QR을 읽으려면 카메라 권한이 필요합니다.")
        Button(onClick = onRequest, modifier = Modifier.heightIn(min = 48.dp)) { Text("카메라 권한 허용") }
        if (denied) OutlinedButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("앱 설정 열기") }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(modifier: Modifier, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onResult by rememberUpdatedState(onQr)
    val view = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var failed by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(view, owner) {
        val disposed = AtomicBoolean()
        val gate = QrScanGate()
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            if (disposed.get()) { proxy.close(); return@setAnalyzer }
            try {
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnCompleteListener(main) { task ->
                        try {
                            if (!disposed.get() && task.isSuccessful) {
                                task.result.forEach { barcode ->
                                    val raw = barcode.rawValue
                                    if (raw != null && gate.accept(raw)) { analysis.clearAnalyzer(); onResult(raw) }
                                    scanError = gate.errorCode
                                }
                            } else if (!disposed.get()) {
                                failed = true
                                analysis.clearAnalyzer()
                            }
                        } finally { proxy.close() }
                    }
            } catch (_: Exception) { proxy.close(); main.execute { if (!disposed.get()) failed = true } }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (!disposed.get()) {
                try {
                    provider = future.get()
                    provider?.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { failed = true }
            }
        }, main)
        onDispose {
            disposed.set(true); gate.close(); analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close(); executor.shutdown()
        }
    }
    Column(modifier) {
        scanError?.let { Text(errorDescription(it), color = MaterialTheme.colorScheme.error) }
        if (failed) Text("카메라를 시작하지 못했습니다. 다른 카메라 앱을 닫고 다시 스캔해 주세요.")
        else AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
