package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.presentation.*
import dev.ene.companion.protocol.PublicMessage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

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

private data class ExpandedThought(val message: PublicMessage, val content: ThoughtContent) {
    override fun toString() = "ExpandedThought"
}

private data class ChatScrollAnchor(val atEnd: Boolean, val messageId: String?, val sourceOffset: Int, val lineOffset: Float)
private class MessageChunks(val text: String, val split: Boolean) {
    val chunks = splitMessageBubbles(text, split).ifEmpty { listOf("") }
    val starts = messageBubbleOffsets(text, chunks)
}
private data class MeasuredBubble(val start: Int, val geometry: BubbleGeometry)

/** 동일 ID의 답변 교체에서도 기존 스크롤 위치와 마지막 진행 항목을 유지한다. */
@Composable
fun ChatHistory(state: ConnectionViewState, modifier: Modifier = Modifier, onEdit: (String) -> Unit, onReroll: (String) -> Unit,
                onVisibleThoughts: (List<String>) -> Unit = {}, onRetryThought: (String) -> Unit = {},
                listState: LazyListState = rememberLazyListState(),
                footer: @Composable () -> Unit = {}) {
    val nearEnd by remember { derivedStateOf { !listState.canScrollForward } }
    val progress = chatActionProgress(state.chatActions, state.processing.phase)
    val split = state.chatDisplay.splitEnabled
    // 분할 결과는 현재 대화에만 보관하고, 변경되지 않은 원문은 재사용한다.
    val chunkCache = remember { mutableMapOf<String, MessageChunks>() }
    val measurements = remember { mutableStateMapOf<Pair<String, Boolean>, MeasuredBubble>() }
    val groups = state.messages.map { message ->
        val cached = chunkCache[message.id]
        message to (cached?.takeIf { it.text == message.text && it.split == split }
            ?: MessageChunks(message.text, split).also { chunkCache[message.id] = it })
    }
    SideEffect { chunkCache.keys.retainAll(state.messages.map { it.id }.toSet()) }
    val expandedThoughts = remember { mutableStateMapOf<String, ExpandedThought>() }
    LaunchedEffect(state.messages, state.thoughts) {
        val messages = state.messages.associateBy { it.id }
        expandedThoughts.keys.toList().forEach { id ->
            val saved = expandedThoughts[id]
            if (saved?.message != messages[id] || saved?.content != state.thoughts[id]) expandedThoughts.remove(id)
        }
    }
    var pendingAnchor by remember { mutableStateOf<ChatScrollAnchor?>(null) }
    val splitAnchor = remember(split) {
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { originalMessageId(it.key as? String ?: "") != null }
        val measured = first?.let { measurements[it.key as String to !split] }
        val geometry = measured?.geometry
        val y = if (first != null && geometry != null) (-first.offset - geometry.textTop).coerceAtLeast(0f) else 0f
        val line = geometry?.layout?.getLineForVerticalPosition(y) ?: 0
        ChatScrollAnchor(!listState.canScrollForward, first?.let { originalMessageId(it.key as String) },
            (measured?.start ?: 0) + (geometry?.layout?.getLineStart(line) ?: 0),
            y - (geometry?.layout?.getLineTop(line) ?: 0f))
    }
    var previousSplit by remember { mutableStateOf(split) }
    val rowCount = groups.sumOf { it.second.chunks.size }
    val visibleCallback by rememberUpdatedState(onVisibleThoughts)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { originalMessageId(it.key as? String ?: "") }.distinct() }
            .distinctUntilChanged().collect { visibleCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { visibleCallback(emptyList()) } }
    // 복원이 끝나기 전에 새 답변이나 다음 분할 변경이 오더라도 원래 앵커를 버리지 않는다.
    LaunchedEffect(split, state.messages) {
        if (previousSplit == split && pendingAnchor == null) return@LaunchedEffect
        val anchor = pendingAnchor ?: splitAnchor
        pendingAnchor = anchor
        if (state.messages.isNotEmpty() && !anchor.atEnd) {
            var index = 0
            for ((message, parts) in groups) {
                if (message.id == anchor.messageId) {
                    val chunk = messageBubbleAtOffset(parts.starts, anchor.sourceOffset)
                    index += chunk
                    val key = messageBubbleKey(message.id, chunk, chunk == parts.chunks.lastIndex) to split
                    listState.scrollToItem(index)
                    val measured = snapshotFlow { measurements[key]?.takeIf {
                        it.geometry.layout.layoutInput.text.text == parts.chunks[chunk]
                    } }.filterNotNull().first()
                    val geometry = measured.geometry
                    val line = geometry.layout.getLineForOffset((anchor.sourceOffset - measured.start).coerceIn(0, parts.chunks[chunk].length))
                    listState.scrollToItem(index, (geometry.textTop + geometry.layout.getLineTop(line) + anchor.lineOffset).roundToInt())
                    break
                }
                index += parts.chunks.size
            }
        } else if (state.messages.isNotEmpty()) {
            listState.scrollToItem(rowCount + if (progress != null) 1 else 0)
        }
        previousSplit = split
        pendingAnchor = null
    }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.chatActions.busy, progress) {
        if (previousSplit == split && pendingAnchor == null && state.messages.isNotEmpty() && nearEnd) {
            listState.scrollToItem(rowCount + if (progress != null) 1 else 0)
        }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("chat-history"), state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.messages.isEmpty()) item {
            Text(if (state.phase == ConnectionPhase.CONNECTED) "아직 표시할 대화가 없습니다." else "PC ENE를 실행하고 같은 Wi-Fi에서 연결해 주세요.", style = MaterialTheme.typography.bodyMedium)
        }
        groups.forEach { (message, parts) ->
            val chunks = parts.chunks
            items(chunks.size, key = { index -> messageBubbleKey(message.id, index, index == chunks.lastIndex) }) { index ->
                val key = messageBubbleKey(message.id, index, index == chunks.lastIndex) to split
                DisposableEffect(key) { onDispose { measurements.remove(key) } }
                val thought = state.thoughts[message.id]
                val expansion = thought?.takeIf { it.status == "available" }?.let { ExpandedThought(message, it) }
                ChatMessageGroup(message, chunks[index], index == chunks.lastIndex, state.chatActions, thought,
                    expansion != null && expandedThoughts[message.id] == expansion, {
                        if (expandedThoughts[message.id] == expansion) expandedThoughts.remove(message.id)
                        else if (expansion != null) expandedThoughts[message.id] = expansion
                    }, onEdit, onReroll, onRetryThought) { measurements[key] = MeasuredBubble(parts.starts[index], it) }
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
