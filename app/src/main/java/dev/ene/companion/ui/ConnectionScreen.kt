package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ene.companion.connection.*
import dev.ene.companion.presentation.*
import kotlin.math.roundToInt

/** 대화와 초안은 메모리에만 둔다. 화면 설정만 별도 로컬 파일에 저장한다. */
@Composable
fun ConnectionScreen(repository: ConnectionRepository, layoutController: ChatLayoutController) {
    val state by repository.state.collectAsStateWithLifecycle()
    val character by repository.characterState.collectAsStateWithLifecycle()
    val placement by repository.characterPlacement.collectAsStateWithLifecycle()
    val layout by layoutController.state.collectAsStateWithLifecycle()
    var camera by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var placementOpen by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val openPlacement: () -> Unit = { keyboard?.hide(); focus.clearFocus(); placementOpen = true }
    val openPairing: () -> Unit = { keyboard?.hide(); focus.clearFocus(); if (state.registered) dialog = "pair" else camera = true }
    DisposableEffect(layoutController) { onDispose { layoutController.finishAdjustment() } }

    Box(Modifier.fillMaxSize()) {
        OverlayChatLayout(layout, layoutController::change, layoutController::finishAdjustment,
            modifier = Modifier.semantics { if (camera) hideFromAccessibility() },
            scene = { CharacterScene(repository, state.phase == ConnectionPhase.CONNECTED && !camera) },
            toolbar = {
                CompanionToolbar(connectionHeading(state), state.registered,
                    state.phase == ConnectionPhase.CONNECTED && character.settings.available && !character.settings.busy,
                    openPlacement) { action ->
                    keyboard?.hide(); focus.clearFocus()
                    when (action) {
                        "pair" -> openPairing()
                        "address" -> {
                            host = state.endpoint?.host.orEmpty()
                            port = (state.endpoint?.port ?: 8765).toString()
                            dialog = "address"
                        }
                        "character" -> repository.openCharacterSettings()
                        else -> dialog = action
                    }
                }
            }) { compact ->
            // 상태 버튼은 메뉴를 열지 않아도 보인다. 긴 사유와 복구 버튼은 스크롤 가능한 상세 창에 둔다.
            val notice = when {
                state.errorCode != null -> errorDescription(state.errorCode!!)
                state.phase != ConnectionPhase.CONNECTED -> connectionHeading(state)
                character.status == "error" -> "캐릭터 표시 실패 · 다시 불러오기"
                state.diagnostics.storageNotice != null -> storageNoticeDescription()
                character.status in setOf("loading", "rendering", "refreshing") -> "캐릭터를 준비하고 있습니다"
                else -> audioOutputDescription(state.audioOutput)
            }
            if (!compact) TextButton(onClick = { dialog = "status" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "연결·캐릭터·음성 상태 자세히"; liveRegion = LiveRegionMode.Polite },
                colors = ButtonDefaults.textButtonColors(contentColor = if (state.errorCode != null || character.status == "error")
                    MaterialTheme.colorScheme.error else Color(0xFFD5DFEA))) {
                Text(notice, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ChatHistory(state, Modifier.weight(1f), repository::openMessageEditor, repository::rerollMessage,
                repository::visibleThoughts, repository::retryThought) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.chatActions.notice?.let { Text(chatActionReason(it), style = MaterialTheme.typography.bodySmall) }
                    chatActionCompatibilityNotice(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    chatDisplayNotice(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.chatActions.editor?.open == false && !state.chatActions.busy) {
                        TextButton(onClick = { repository.reopenMessageEditor() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("보관된 편집 초안 보기") }
                    }
                    if (layout.saveStatus == "error") {
                        TextButton(onClick = layoutController::retrySave, modifier = Modifier.heightIn(min = 48.dp)) { Text("대화창 높이 저장 실패 · 재시도") }
                    } else if (layout.readFailed) {
                        Text("대화창 높이를 읽지 못해 기본값을 표시합니다. 배치에서 조절하면 다시 저장합니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    state.sendState?.let { send ->
                        Text(when (send) {
                            "reserved" -> "PC에서 전송을 준비하고 있습니다."
                            "accepted" -> "PC에 접수되었습니다."
                            else -> "전송 결과를 확인하고 있습니다."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .08f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(state.draft, onValueChange = repository::editDraft,
                    placeholder = { Text("메시지 보내기") }, modifier = Modifier.weight(1f).semantics {
                        contentDescription = "메시지 · 입력 내용은 앱 종료 시 삭제됩니다"
                    }, maxLines = if (compact) 1 else 3, shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF1B1D22),
                        unfocusedContainerColor = Color(0xFF1B1D22), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                Button(onClick = { repository.sendDraft() }, enabled = state.canSend, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0071E3), contentColor = Color.White)) { Text("전송") }
            }
        }
        if (camera) Surface(Modifier.fillMaxSize()) {
            PairingCameraScreen(Modifier.safeDrawingPadding(), onQr = { camera = false; repository.pair(it) }, onClose = { camera = false })
        }
    }
    if (placementOpen) CharacterPlacementSheet(placement, repository::changeCharacterPlacement, repository::finishCharacterPlacement,
        repository::resetCharacterPlacement, repository::retryCharacterPlacementSave,
        { repository.finishCharacterPlacement(); layoutController.finishAdjustment(); placementOpen = false }) {
        ChatHeightSettings(layout, layoutController::change, layoutController::finishAdjustment,
            layoutController::reset, layoutController::retrySave)
    }
    if (character.settings.open) CharacterSettingsSheet(character.settings, repository::closeCharacterSettings,
        repository::previewCharacterSettings, repository::submitCharacterSettings)
    state.chatActions.editor?.takeIf { it.open }?.let { editor ->
        MessageEditDialog(editor, repository::editMessageDraft, { repository.submitMessageEdit() }, repository::cancelMessageEditor)
    }
    when (dialog) {
        "status" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("연결 및 재생 상태") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionStatus(state)
                when (state.phase) {
                    ConnectionPhase.UNREGISTERED -> TextButton(onClick = { dialog = null; openPairing() }) { Text("QR 연결") }
                    ConnectionPhase.AWAITING_APPROVAL -> TextButton(onClick = { repository.cancelPairing() }) { Text("승인 대기 취소") }
                    ConnectionPhase.ACTION_REQUIRED, ConnectionPhase.RECONNECTING, ConnectionPhase.PAUSED ->
                        TextButton(onClick = { repository.retry() }) { Text("다시 연결") }
                    else -> Unit
                }
                if (state.phase == ConnectionPhase.CONNECTED) CharacterStatus(character, repository::retryCharacter)
                Text("대화와 입력 초안은 앱이 종료되면 사라집니다.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("닫기") } })
        "diagnostics" -> ConnectionDiagnosticsDialog(state.diagnostics) { dialog = null }
        "pair" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("새 QR로 연결할까요?") },
            text = { Text("새 연결에는 PC 승인이 필요합니다. 승인될 때까지 기존 등록은 보관됩니다.") },
            confirmButton = { TextButton(onClick = { dialog = null; camera = true }) { Text("QR 스캔") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
        "forget" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("이 기기의 등록을 지울까요?") },
            text = { Text("앱의 인증 정보, 표시된 대화와 초안을 지웁니다. PC의 대화나 등록은 지우지 않습니다. 다시 연결하려면 QR과 PC 승인이 필요합니다.") },
            confirmButton = { TextButton(onClick = { dialog = null; repository.forget() }) { Text("등록 해제") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
        "address" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("PC 접속 주소") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("주소만 바꿉니다. PC 인증서는 바꾸지 않습니다.")
                OutlinedTextField(host, { host = it }, label = { Text("주소") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("포트") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = { repository.updateAddress(host, port.toIntOrNull() ?: 0); dialog = null }) { Text("저장 후 연결") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
    }
}

@Composable
internal fun ChatHeightSettings(state: ChatLayoutState, onChange: (Float) -> Unit, onFinish: () -> Unit,
                                onReset: () -> Unit, onRetry: () -> Unit) {
    val percent = (state.layout.heightFraction * 100).roundToInt()
    Text("대화창 높이 · ${percent}%", style = MaterialTheme.typography.labelLarge)
    Slider(state.layout.heightFraction, onValueChange = onChange, onValueChangeFinished = onFinish,
        valueRange = ChatLayout.MIN_FRACTION..ChatLayout.MAX_FRACTION, enabled = state.loaded,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "대화창 높이 설정"; stateDescription = "${percent}%" })
    Text(when {
        !state.loaded -> "저장된 대화창 높이를 불러오는 중입니다."
        state.saveStatus == "error" -> "높이를 저장하지 못했습니다. 마지막 저장값은 유지됩니다."
        state.saveStatus in setOf("dirty", "saving") -> "높이 저장 중…"
        state.readFailed -> "높이를 읽지 못해 기본값을 표시합니다. 직접 조절하거나 기본 높이를 복원하면 다시 저장합니다."
        else -> "높이 저장됨 · 키보드와 작은 화면에서는 표시 높이만 자동 보정됩니다."
    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.saveStatus == "error") TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("높이 저장 재시도") }
    TextButton(onClick = onReset, enabled = state.loaded, modifier = Modifier.heightIn(min = 48.dp)) { Text("기본 대화창 높이 복원") }
    HorizontalDivider()
}

internal fun chatDisplayNotice(state: ConnectionViewState): String? =
    if (state.phase != ConnectionPhase.CONNECTED) null else when (state.chatDisplay.status) {
        "unsupported" -> "PC ENE를 업데이트하면 PC의 메시지 분할 설정을 함께 사용합니다."
        "syncing" -> "PC의 대화 표시 설정을 확인하고 있습니다."
        "error" -> "대화 표시 설정을 확인하지 못했습니다. 마지막 표시를 유지합니다. 화면 복귀 시 다시 확인합니다."
        else -> null
    }

internal fun connectionHeading(state: ConnectionViewState): String = when (state.phase) {
    ConnectionPhase.UNREGISTERED -> "PC 연결 필요"
    ConnectionPhase.CONNECTING -> "연결 중"
    ConnectionPhase.AWAITING_APPROVAL -> "PC 승인 대기"
    ConnectionPhase.SYNCING -> "대화 동기화 중"
    ConnectionPhase.CONNECTED -> "암호화 연결됨"
    ConnectionPhase.RECONNECTING -> "재연결 중"
    ConnectionPhase.PAUSED -> "연결 일시 중지"
    ConnectionPhase.ACTION_REQUIRED -> "연결 확인 필요"
}

@Composable
fun ConnectionStatus(state: ConnectionViewState) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(when (state.phase) {
            ConnectionPhase.UNREGISTERED -> "PC 연결이 필요합니다"
            ConnectionPhase.CONNECTING -> "PC에 연결 중입니다"
            ConnectionPhase.AWAITING_APPROVAL -> "PC에서 연결 요청을 승인해 주세요"
            ConnectionPhase.SYNCING -> "현재 전체 대화를 가져오고 있습니다 · 전송 대기"
            ConnectionPhase.CONNECTED -> if (state.processing.phase == "idle") "암호화 연결됨" else "PC가 응답을 처리하고 있습니다"
            ConnectionPhase.RECONNECTING -> "연결이 끊겼습니다 · 재접속 중"
            ConnectionPhase.PAUSED -> "연결이 일시 중지되었습니다"
            ConnectionPhase.ACTION_REQUIRED -> "연결을 확인해 주세요"
        }, style = MaterialTheme.typography.titleSmall)
        if (state.phase == ConnectionPhase.CONNECTED) Text(audioOutputDescription(state.audioOutput), style = MaterialTheme.typography.bodySmall)
        if (state.diagnostics.stage !in setOf(ConnectionStage.IDLE, ConnectionStage.CONNECTED)) Text(connectionStageDescription(state.diagnostics.stage), style = MaterialTheme.typography.bodySmall)
        if (state.phase != ConnectionPhase.CONNECTED) state.diagnostics.discoveryNotice?.let { Text(discoveryDescription(it), style = MaterialTheme.typography.bodySmall) }
        state.diagnostics.storageNotice?.let { Text(storageNoticeDescription(), style = MaterialTheme.typography.bodySmall) }
        state.errorCode?.let { Text(errorDescription(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.messages.isNotEmpty() && state.phase != ConnectionPhase.CONNECTED) Text("마지막으로 받은 대화입니다. 동기화가 끝나면 갱신됩니다.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun errorDescription(code: String): String = when (code) {
    "character_cache_cleanup_failed" -> "기존 캐릭터 파일을 지우지 못해 등록 변경을 중단했습니다. 앱을 다시 연 뒤 재시도해 주세요."
    "tls_expired", "tls_repair_required", "registration_lost", "registration_changed", "authorization_revoked" -> "등록을 확인할 수 없습니다. PC에서 새 QR을 발급해 다시 승인해 주세요."
    "tls_clock_invalid" -> "휴대폰과 PC의 날짜·시간을 확인해 주세요."
    "tls_identity_invalid", "server_mismatch" -> "등록한 PC의 인증서와 일치하지 않아 연결을 차단했습니다. 주소 또는 PC의 새 QR을 확인해 주세요."
    "authorization_unconfirmed" -> "인증 결과를 확인하지 못했습니다. 등록은 지우지 않았습니다. PC와 주소를 확인해 주세요."
    "pairing_expired", "pairing_denied", "pairing_rejected" -> "QR이 만료되었거나 승인이 거절되었습니다. PC에서 새 QR을 발급해 주세요."
    "text_too_large" -> "메시지가 너무 깁니다. UTF-8 기준 16 KiB 이내로 줄여 주세요."
    "snapshot_too_large" -> "현재 대화가 모바일 전송 한도를 넘었습니다. PC에서 확인해 주세요."
    "conversation_changed" -> "PC 실행이나 대화가 바뀌어 자동 재전송하지 않았습니다. 대화를 확인한 뒤 직접 전송해 주세요."
    "pc_unreachable", "heartbeat_timeout", "connection_closed", "snapshot_timeout", "request_status_timeout" -> "PC ENE 실행 상태와 같은 Wi-Fi 연결 여부를 확인해 주세요."
    else -> "작업을 마치지 못했습니다. 입력·주소·PC 상태를 확인해 주세요. (${diagnosticCode(code)})"
}
