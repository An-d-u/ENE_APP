package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ene.companion.connection.*

/** 텍스트는 Repository 메모리만 사용한다. 화면 복원이나 파일에 대화를 남기지 않는다. */
@Composable
fun ConnectionScreen(repository: ConnectionRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    var camera by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    Scaffold { padding ->
        if (camera) PairingCameraScreen(Modifier.padding(padding), onQr = { camera = false; repository.pair(it) }, onClose = { camera = false })
        else Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ENE 동반 앱", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            ConnectionStatus(state)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (state.registered) dialog = "pair" else camera = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("QR 연결") }
                if (state.registered) OutlinedButton(onClick = {
                    host = state.endpoint?.host.orEmpty(); port = (state.endpoint?.port ?: 8765).toString(); dialog = "address"
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("주소 수정") }
                TextButton(onClick = { dialog = "forget" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("등록 해제") }
                TextButton(onClick = { dialog = "diagnostics" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("연결 진단") }
            }
            if (state.phase == ConnectionPhase.AWAITING_APPROVAL) {
                TextButton(onClick = { repository.cancelPairing() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("승인 대기 취소") }
            } else if (state.phase == ConnectionPhase.ACTION_REQUIRED || state.phase == ConnectionPhase.RECONNECTING) {
                TextButton(onClick = { repository.retry() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 연결") }
            }
            CharacterPanel(repository, controlsVisible = state.phase == ConnectionPhase.CONNECTED)
            ChatHistory(state, Modifier.weight(1f), { repository.openMessageEditor(it) }, { repository.rerollMessage(it) })
            state.chatActions.notice?.let { Text(chatActionReason(it), style = MaterialTheme.typography.bodySmall) }
            chatActionCompatibilityNotice(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.chatActions.editor?.open == false && !state.chatActions.busy) {
                TextButton(onClick = { repository.reopenMessageEditor() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("보관된 편집 초안 보기") }
            }
            if (state.sendState != null) Text(when (state.sendState) { "reserved" -> "PC에서 전송을 준비하고 있습니다."; "accepted" -> "PC에 접수되었습니다."; else -> "전송 결과를 확인하고 있습니다." }, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(state.draft, onValueChange = { repository.editDraft(it) }, label = { Text("메시지") }, modifier = Modifier.weight(1f), maxLines = 4,
                    supportingText = { Text("입력 내용은 앱이 종료되면 사라집니다.") })
                Button(onClick = { repository.sendDraft() }, enabled = state.canSend, modifier = Modifier.heightIn(min = 56.dp).padding(top = 8.dp)) { Text("전송") }
            }
        }
    }
    state.chatActions.editor?.takeIf { it.open }?.let { editor ->
        MessageEditDialog(editor, { repository.editMessageDraft(it) }, { repository.submitMessageEdit() }, { repository.cancelMessageEditor() })
    }
    when (dialog) {
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("주소만 바꿉니다. PC 인증서는 바꾸지 않습니다.")
                OutlinedTextField(host, { host = it }, label = { Text("주소") }, singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("포트") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = { repository.updateAddress(host, port.toIntOrNull() ?: 0); dialog = null }) { Text("저장 후 연결") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } })
    }
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
