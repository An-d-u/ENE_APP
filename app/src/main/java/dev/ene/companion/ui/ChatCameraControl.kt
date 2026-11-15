package dev.ene.companion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ene.companion.camera.ChatCamera
import dev.ene.companion.camera.hasCameraPermission
import dev.ene.companion.camera.shouldOpenCamera
import dev.ene.companion.connection.CameraShareView
import dev.ene.companion.connection.ConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatCameraControl(repository: ConnectionRepository, state: CameraShareView,
                               connected: Boolean, open: Boolean, onDismiss: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    var permission by remember { mutableStateOf(hasCameraPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var error by remember { mutableStateOf<String?>(null) }
    var binding by remember { mutableStateOf<ChatCamera?>(null) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        denied = !it
    }
    DisposableEffect(owner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permission = hasCameraPermission(context)
                    resumed = true
                    if (!permission) repository.stopCameraShare()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    resumed = false
                    binding?.close()
                    repository.stopCameraShare()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            binding?.close()
            repository.stopCameraShare()
        }
    }
    val capture = state.shouldOpenCamera(connected, permission, resumed)
    DisposableEffect(repository, owner, state.generation, rotation, capture) {
        if (!capture) return@DisposableEffect onDispose {}
        val epoch = state.generation
        fun current() = repository.cameraShare.value.let { it.generation == epoch && it.available && (it.question || it.observe) }
        val camera = ChatCamera(context, owner, epoch, state.lens, rotation, ::current,
            { repository.cameraShare.value.takeIf { it.generation == epoch && it.observe && it.observeAllowed }?.observeEpoch },
            { ownership, jpeg -> repository.submitCameraObservation(epoch, ownership, jpeg) },
            { if (current()) {
                error = "선택한 카메라를 열 수 없어 공유를 종료했습니다."
                repository.stopCameraShare()
            } })
        binding = camera
        repository.installCameraSource(epoch, camera::question)
        camera.start()
        onDispose {
            camera.close()
            repository.installCameraSource(epoch, null)
            if (binding === camera) binding = null
        }
    }
    if (open) CameraShareDialog(state, connected && resumed, permission, denied, error, binding,
        onPermission = { grant.launch(Manifest.permission.CAMERA) },
        onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
        onConfigure = { q, o, lens ->
            if (hasCameraPermission(context) && resumed) {
                error = null
                repository.configureCameraShare(q, o, lens)
            } else permission = false
        }, onStop = { binding?.close(); repository.stopCameraShare() }, onDismiss = onDismiss)
}

@Composable
internal fun CameraShareDialog(state: CameraShareView, connected: Boolean, permission: Boolean,
                              denied: Boolean, error: String?, binding: ChatCamera?,
                              onPermission: () -> Unit, onSettings: () -> Unit,
                              onConfigure: (Boolean, Boolean, String) -> Unit,
                              onStop: () -> Unit, onDismiss: () -> Unit) {
    val active = state.question || state.observe
    val context = LocalContext.current
    val lenses by produceState<Set<String>?>(null, permission) {
        value = if (!permission) null else withContext(Dispatchers.IO) {
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.cameraIdList.mapNotNull { id ->
                    when (manager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING]) {
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        else -> null
                    }
                }.toSet()
            } catch (_: Exception) { emptySet() }
        }
    }
    var lens by remember(state.generation) { mutableStateOf(state.lens) }
    var question by remember(state.generation) { mutableStateOf(!active || state.question) }
    var observe by remember(state.generation) { mutableStateOf(state.observe) }
    val supported = connected && state.supported && state.available
    fun change(q: Boolean, o: Boolean, next: String) {
        question = q; observe = o; lens = next
        if (active) {
            if (!q && !o) onStop() else onConfigure(q, o, next)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("카메라 공유") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when {
                !connected -> "PC ENE 연결 후 사용할 수 있습니다."
                !state.supported -> "카메라 공유를 지원하는 PC ENE가 필요합니다."
                !state.available -> "PC에서 이미지를 받을 수 있는 모델을 선택해 주세요."
                state.remote -> "이미지를 받을 외부 모델 · ${state.model}"
                else -> "이미지를 받을 로컬 모델 · ${state.model}"
            })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (supported && !permission) {
                Text(if (denied) "카메라 권한이 꺼져 있습니다." else "카메라 공유에 사용할 권한을 허용해 주세요.")
                OutlinedButton(onClick = onPermission) { Text("카메라 권한 허용") }
                if (denied) TextButton(onClick = onSettings) { Text("앱 설정 열기") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in listOf("back" to "후면", "front" to "전면")) {
                    FilterChip(selected = lens == id, onClick = { change(question, observe, id) },
                        label = { Text(label) }, enabled = supported && lenses?.contains(id) == true)
                }
            }
            if (permission && lenses?.isEmpty() == true) Text("사용 가능한 전면·후면 카메라가 없습니다.")
            CameraMode("질문할 때 카메라 보기", question, supported) { change(it, observe, lens) }
            CameraMode("카메라를 보고 먼저 말 걸기", observe, supported) { change(question, it, lens) }
            if (active && state.observe && !state.observeAllowed) Text("자동 관찰 대기 중", style = MaterialTheme.typography.bodySmall)
            if (binding != null && active && binding.generation == state.generation) {
                key(binding) { CameraPreview(binding) }
            }
            Text("앱에서 나가거나 PC 연결이 끊기면 공유가 종료됩니다.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        if (active) TextButton(onClick = onStop) { Text("공유 종료") }
        else Button(onClick = { onConfigure(question, observe, lens) },
            enabled = supported && permission && lenses?.contains(lens) == true && (question || observe)) { Text("공유 시작") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

@Composable
private fun CameraMode(label: String, checked: Boolean, enabled: Boolean, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheck),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CameraPreview(binding: ChatCamera) {
    val context = LocalContext.current
    val view = remember(binding) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
        contentDescription = "공유 카메라 미리보기"
    } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(112.dp))
    DisposableEffect(view, binding) {
        binding.previewOn(view.surfaceProvider)
        onDispose { binding.previewOn(null) }
    }
}
