package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*

internal fun chatActionCompatibilityNotice(state: ConnectionViewState): String? =
    if (state.phase == ConnectionPhase.CONNECTED && !state.chatActions.supported)
        "PC ENE를 업데이트하면 메시지 수정·리롤을 사용할 수 있습니다." else null

internal fun messageAction(id: String, role: String, state: ChatActionsViewState): String? = when {
    !state.supported -> null
    role == "user" && id == state.userMessageId -> "edit"
    role == "assistant" && id == state.assistantMessageId -> "reroll"
    else -> null
}

internal fun chatActionReason(code: String?): String = when (code) {
    "ready" -> ""
    "busy" -> "진행 중인 작업이 끝나면 사용할 수 있습니다."
    "syncing" -> "PC의 최신 대화를 확인하고 있습니다."
    "not_supported" -> "PC ENE를 최신 버전으로 업데이트해 주세요."
    "no_target", "stale_target", "revision_changed", "conversation_changed" -> "대상이 바뀌었습니다. 최신 대화를 확인해 주세요. 편집 초안은 보관됩니다."
    "ai_unavailable" -> "PC에서 AI 연결 상태를 확인해 주세요."
    "text_too_large" -> "UTF-8 기준 16 KiB를 넘습니다. 내용을 줄이거나 PC에서 수정해 주세요."
    "invalid_message" -> "빈 메시지는 보낼 수 없습니다. 입력 내용을 확인해 주세요."
    "unsupported_command" -> "파일 관련 명령은 PC에서 사용해 주세요."
    "result_unknown" -> "결과를 확정하지 못해 자동 재실행하지 않았습니다. 현재 대화를 확인한 뒤 직접 다시 시도해 주세요."
    else -> "작업을 마치지 못했습니다. 현재 대화를 확인한 뒤 다시 시도해 주세요."
}

internal fun chatActionProgress(state: ChatActionsViewState, processing: String): String? = when {
    state.progress == "confirming" -> "변경된 대화를 확인하고 있습니다."
    state.progress in setOf("reserved", "accepted") -> "답변을 다시 생성하고 있습니다."
    state.progress != null -> "수정·리롤 요청 결과를 확인하고 있습니다."
    processing != "idle" -> "생각 중…"
    else -> null
}

@Composable
fun ChatMessageActions(id: String, role: String, state: ChatActionsViewState, onEdit: (String) -> Unit, onReroll: (String) -> Unit) {
    val kind = messageAction(id, role, state) ?: return
    val enabled = if (kind == "edit") state.canEdit else state.canReroll
    val reason = if (kind == "edit") state.editReason else state.rerollReason
    Column {
        TextButton(onClick = { if (kind == "edit") onEdit(id) else onReroll(id) }, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current,
                disabledContentColor = LocalContentColor.current.copy(alpha = .6f)),
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(if (kind == "edit") "수정" else "리롤")
        }
        if (!enabled) Text(chatActionReason(reason), style = MaterialTheme.typography.bodySmall)
    }
}

/** 동일 ID의 답변 교체에서도 기존 스크롤 위치와 마지막 진행 항목을 유지한다. */
@Composable
fun ChatHistory(state: ConnectionViewState, modifier: Modifier = Modifier, onEdit: (String) -> Unit, onReroll: (String) -> Unit,
                footer: @Composable () -> Unit = {}) {
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { !listState.canScrollForward } }
    val progress = chatActionProgress(state.chatActions, state.processing.phase)
    LaunchedEffect(state.messages.lastOrNull()?.id, state.chatActions.busy, progress) {
        if (nearEnd && state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size + if (progress != null) 1 else 0)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.messages.isEmpty()) item {
            Text(if (state.phase == ConnectionPhase.CONNECTED) "아직 표시할 대화가 없습니다." else "PC ENE를 실행하고 같은 Wi-Fi에서 연결해 주세요.", style = MaterialTheme.typography.bodyMedium)
        }
        items(state.messages, key = { it.id }) { message ->
            val user = message.role == "user"
            Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
              Surface(color = if (user) ChatUserColor else ChatAssistantColor,
                contentColor = if (user) Color.White else Color(0xFF111827),
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.88f), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (message.role == "user") "나" else "ENE", style = MaterialTheme.typography.labelMedium)
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    if (message.attachment_unsupported == true) Text("첨부 내용은 PC에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                    ChatMessageActions(message.id, message.role, state.chatActions, onEdit, onReroll)
                }
              }
            }
        }
        if (progress != null) item(key = "chat-action-progress") {
            Text(progress, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        }
        item(key = "chat-notices") { footer() }
    }
}

@Composable
fun MessageEditDialog(editor: MessageEditor, onText: (String) -> Unit, onSubmit: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("마지막 메시지 수정") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기존 메시지와 답변을 교체합니다. 새 메시지 입력창의 초안은 바뀌지 않습니다.")
            if (editor.attachmentRetained) Text("기존 첨부는 PC에 유지되며 수정한 텍스트와 함께 사용됩니다.")
            OutlinedTextField(editor.text, onText, label = { Text("수정할 내용") }, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth(), supportingText = { Text("${editor.text.toByteArray(Charsets.UTF_8).size} / 16,384 바이트 · 앱 종료 시 초안 삭제") })
            editor.reason?.let { Text(chatActionReason(it), style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = onSubmit, enabled = editor.canSubmit, modifier = Modifier.heightIn(min = 48.dp)) { Text("수정 후 재생성") }
    }, dismissButton = {
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
    })
}
