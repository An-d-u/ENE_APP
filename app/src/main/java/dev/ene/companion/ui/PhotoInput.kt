package dev.ene.companion.ui

import android.graphics.BitmapFactory
import android.Manifest
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ConnectionRepository
import dev.ene.companion.connection.ConnectionViewState
import dev.ene.companion.input.ChatThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed

@Composable
internal fun PhotoPreview(bytes: ByteArray, description: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..320 || bounds.outHeight !in 1..320) null
            else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Box(modifier) { bitmap?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
}

@Composable
internal fun PhotoDraftPanel(repository: ConnectionRepository, state: ConnectionViewState, compact: Boolean = false) {
    val photos by repository.photos.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (compact && (photos.images.isNotEmpty() || photos.preparing || photos.notice != null || state.inputNotice != null || state.photoRetry)) {
        TextButton(onClick = { expanded = true }) { Text("첨부 사진 ${photos.images.size}장 · 상태 및 편집") }
        if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("첨부 사진") },
            text = { Column { PhotoDraftPanel(repository, state) } },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("닫기") } })
        return
    }
    if (photos.images.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            photos.images.forEachIndexed { index, photo ->
                Column {
                    PhotoPreview(photo.preview, "첨부할 사진 ${index + 1}", Modifier.size(72.dp))
                    TextButton(onClick = { repository.removePhoto(photo.id) }, enabled = state.sendState == null) { Text("제거") }
                }
            }
        }
        Text("사진은 전송 시 PC와 설정된 AI 제공자에게 전달됩니다.", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
    }
    if (photos.preparing) Text("사진 준비 중…", Modifier.padding(horizontal = 12.dp))
    (photos.notice ?: state.inputNotice)?.let { Text(inputNotice(it), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
    if (state.photoRetry) TextButton(onClick = { repository.retryPhotos() }) { Text("미접수 사진 다시 전송") }
}

@Composable
internal fun InputMenu(repository: ConnectionRepository, state: ConnectionViewState) {
    var open by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var permissionKey by remember { mutableStateOf<Any?>(null) }
    var permissionStart by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { permissionKey = null; permissionStart?.cancel() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); permissionStart?.cancel() }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected = permissionKey
        permissionKey = null
        if (granted && expected != null) permissionStart = scope.launch {
            // 권한 결과가 onResume보다 먼저 도착할 수 있다. 같은 연결의 일회성 시작만 재개한다.
            withTimeoutOrNull(5000) { lifecycle.withResumed { repository.startRecording(expected) } }
        } else if (!granted) repository.microphoneDenied()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { values ->
        repository.selectPhotos(values.map { it.toString() })
    }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("＋") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("사진 선택") }, enabled = state.photoSupported && state.sendState == null && !state.photoPreparing,
                onClick = { open = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            if (!state.photoSupported) DropdownMenuItem(text = { Text("사진 전송은 PC 업데이트·연결이 필요합니다") }, enabled = false, onClick = {})
            DropdownMenuItem(text = { Text("음성 입력 · PC에서 인식") }, enabled = state.sttSupported &&
                state.phase == dev.ene.companion.connection.ConnectionPhase.CONNECTED && state.processing.phase == "idle" && state.sendState == null && !state.stt.busy,
                onClick = { open = false; permissionKey = repository.recordingPermissionKey(); microphone.launch(Manifest.permission.RECORD_AUDIO) })
        }
    }
}

@Composable
internal fun MessagePhotos(images: List<ChatThumbnail>) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        images.forEachIndexed { index, image -> PhotoPreview(image.bytes, "보낸 사진 ${index + 1}", Modifier.size(128.dp)) }
    }
}
internal fun inputNotice(code: String): String = when (code) {
    "image_input_unsupported" -> "PC에서 선택한 AI 모델은 사진 입력을 지원하지 않습니다."
    "busy" -> "PC의 응답 처리가 끝나면 사진을 보낼 수 있습니다."
    "photo_count_limit" -> "사진은 한 번에 최대 4장까지 첨부할 수 있습니다."
    "animated_image", "unsupported_image" -> "정지 사진(JPEG·PNG·WebP)을 선택해 주세요."
    "image_dimensions", "image_too_large" -> "사진의 크기가 제한을 넘습니다. 더 작은 사진을 선택해 주세요."
    "photo_send_failed" -> "사진을 보내지 못했습니다. 초안은 보관됩니다. 연결 상태를 확인하고 다시 시도해 주세요."
    else -> "입력을 처리하지 못했습니다. 선택한 파일과 PC 상태를 확인해 주세요."
}

@Composable
internal fun SttPanel(repository: ConnectionRepository, state: ConnectionViewState) {
    if (state.stt.busy || state.stt.reason != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (state.stt.phase) {
                "starting" -> "PC의 음성 입력 설정 확인 중…"
                "recording" -> "녹음 중 · 최대 ${state.stt.maximumMs / 1000}초"
                "recorded" -> "최대 길이로 녹음이 멈췄습니다. 완료를 누르면 PC에서 인식합니다."
                "uploading" -> "녹음 전달 중…"
                "transcribing" -> "PC에서 음성을 인식하고 있습니다…"
                else -> when (state.stt.reason) {
                    "microphone_permission" -> "음성 입력에는 마이크 권한이 필요합니다."
                    "recording_too_short" -> "녹음이 너무 짧습니다. 다시 녹음해 주세요."
                    "no_speech" -> "인식된 음성이 없습니다. 다시 녹음해 주세요."
                    "stt_busy", "busy" -> "PC의 작업이 끝난 뒤 음성 입력을 다시 시작해 주세요."
                    "recording_cancelled", "recording_interrupted" -> "녹음이 취소되었습니다. 자동으로 다시 시작하지 않습니다."
                    "text_too_large" -> "인식 결과와 초안을 합치면 16 KiB를 넘습니다. 초안을 줄여 주세요."
                    else -> "음성을 인식하지 못했습니다. PC의 STT 설정을 확인해 주세요."
                }
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (state.stt.phase in setOf("recording", "recorded")) TextButton(onClick = repository::finishRecording) { Text("완료") }
            if (state.stt.busy) TextButton(onClick = repository::cancelRecording) { Text("취소") }
        }
    }
    state.sttReview?.let { text ->
        AlertDialog(onDismissRequest = repository::discardTranscription, title = { Text("음성 인식 결과") },
            text = { Column {
                Text("녹음 중 초안이 바뀌었습니다. 확인 후 현재 입력창에 추가해 주세요.")
                Text(text, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = { repository.appendTranscription() }) { Text("현재 초안에 추가") } },
            dismissButton = { TextButton(onClick = repository::discardTranscription) { Text("버리기") } })
    }
}
