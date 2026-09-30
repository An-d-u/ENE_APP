package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import java.util.UUID

/** 편집 내용은 앱 메모리에서만 보관하고 진단 문자열에는 남기지 않는다. */
data class MessageEditor(
    val targetId: String, val serverEpoch: String, val conversationId: String,
    val revision: Long, val text: String, val attachmentRetained: Boolean,
    val open: Boolean = true, val canSubmit: Boolean = false, val reason: String? = null,
) {
    override fun toString(): String = "MessageEditor(open=$open)"
}

data class ChatActionsViewState(
    val supported: Boolean = false,
    val userMessageId: String? = null, val assistantMessageId: String? = null,
    val canEdit: Boolean = false, val canReroll: Boolean = false,
    val editReason: String = "syncing", val rerollReason: String = "syncing",
    val busy: Boolean = false, val progress: String? = null, val notice: String? = null,
    val editor: MessageEditor? = null,
) {
    override fun toString(): String = "ChatActionsViewState(supported=$supported, busy=$busy)"
}

/** 일반 전송 초안과 분리된 단일 조작·조회 상태. 자동 재생성 경로는 없다. */
internal class ChatActionState(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private class Pending(val message: ChatAction) {
        var state = "sending"
        var querying = false
        var terminal: String? = null
        var code: String? = null
    }
    private class Query(val message: ChatActionsRequest, var deadline: Long) {
        var response: ChatActionsState? = null
    }
    private var identity: String? = null
    private var server: Ready? = null
    private var context: ExtensionContext? = null
    private var current: ConversationSnapshot? = null
    private var synced = false
    private var supported = false
    private var latest: ChatActionsState? = null
    private var editor: MessageEditor? = null
    private var pending: Pending? = null
    private var query: Query? = null
    private var needsRefresh = false
    private var retryAfter = 0L
    private var notice: String? = null

    val awaitingResult: Boolean get() = pending?.let { it.terminal == null && (it.state == "sending" || it.querying) } == true
    val hasPending: Boolean get() = pending != null
    val refreshing: Boolean get() = needsRefresh || query?.message?.refresh == true

    private fun matchesScope(epoch: String, conversation: String) = server?.let {
        it.server_epoch == epoch && it.conversation_id == conversation
    } == true

    fun connected(registration: String, ready: Ready) {
        if (identity != null && identity != registration) forget()
        identity = registration
        server = ready
        context = null
        current = null
        latest = null
        synced = false
        query = null
        retryAfter = 0
        supported = "chat_actions_v1" in ready.capabilities
        dropChangedScope()
    }

    private fun dropChangedScope() {
        val request = pending?.message
        if (request != null && !matchesScope(request.server_epoch, request.conversation_id)) {
            pending = null; needsRefresh = false; notice = "conversation_changed"
        }
    }

    fun ready(message: ExtensionsReady) {
        val ready = server ?: return
        if (!supported || context != null) return
        val candidate = ExtensionContext(ready.registration_generation, ready.server_epoch, message.connection_generation, ready.conversation_id)
        try { ExtensionCodec.validate(message, candidate, ready.capabilities.toSet(), "from_pc") }
        catch (_: ProtocolException) { return }
        context = candidate
    }

    fun baseState(epoch: String, conversation: String, synchronized: Boolean) {
        if (!matchesScope(epoch, conversation)) {
            server = server?.copy(server_epoch = epoch, conversation_id = conversation)
            context = context?.copy(serverEpoch = epoch, conversationId = conversation)
            current = null; latest = null; query = null; retryAfter = 0
            dropChangedScope()
        }
        synced = synchronized
    }

    fun snapshot(value: ConversationSnapshot) {
        if (!matchesScope(value.serverEpoch, value.conversationId)) return
        current = value
        synced = true
        settleQuery()
    }

    fun disconnected() {
        context = null; synced = false; query = null; retryAfter = 0
    }

    fun forget() {
        identity = null; server = null; context = null; current = null; latest = null
        pending = null; query = null; editor = null; notice = null
        synced = false; supported = false; needsRefresh = false; retryAfter = 0
    }

    private fun agrees(state: ChatActionsState): Boolean {
        val snapshot = current ?: return false
        return snapshot.serverEpoch == state.server_epoch && snapshot.conversationId == state.conversation_id &&
            snapshot.eventSeq == state.event_seq && snapshot.conversationRevision == state.conversation_revision
    }

    fun receiveState(value: ChatActionsState) {
        val connection = context ?: return
        try { ExtensionCodec.validate(value, connection, requireNotNull(server).capabilities.toSet(), "from_pc") }
        catch (_: ProtocolException) { return }
        val activeQuery = query
        if (value.state_seq > (latest?.state_seq ?: 0)) latest = value
        if (activeQuery != null && activeQuery.message.query_id == value.query_id) {
            activeQuery.response = value
            if (value.state_seq < (latest?.state_seq ?: 0)) {
                query = null; retryAfter = nowMillis() + 500
            }
        }
        settleQuery()
    }

    private fun settleQuery() {
        val activeQuery = query ?: return
        val reply = activeQuery.response ?: return
        val snapshot = current ?: return
        if (!synced || !agrees(reply)) {
            // 더 최신 대화가 설치되었으면 낡은 조회를 기다리지 않는다.
            if (snapshot.eventSeq > reply.event_seq || snapshot.conversationRevision > reply.conversation_revision) {
                query = null; retryAfter = nowMillis() + 500
            }
            return
        }
        if (activeQuery.message.refresh && reply.snapshot_id != snapshot.snapshotId) return
        query = null
        retryAfter = nowMillis() + 500
        if (activeQuery.message.refresh) {
            needsRefresh = false
            val finished = pending
            if (finished?.terminal != null) {
                if (finished.terminal == "completed") {
                    if (finished.message.kind == "edit") editor = null
                    notice = null
                } else notice = if (finished.terminal == "unknown") "result_unknown" else finished.code ?: "request_failed"
                pending = null
            }
        }
    }

    fun nextQuery(): ChatActionsRequest? {
        val connection = context ?: return null
        if (!supported || query != null || nowMillis() < retryAfter) return null
        if (!needsRefresh && (!synced || current == null || latest?.let(::agrees) == true)) return null
        val request = ChatActionsRequest(connection.registrationGeneration, connection.serverEpoch,
            connection.connectionGeneration, connection.conversationId, idFactory(), needsRefresh)
        query = Query(request, nowMillis() + 10_000)
        return request
    }

    fun checkTimeout(snapshotInProgress: Boolean) {
        val activeQuery = query ?: return
        if (snapshotInProgress && activeQuery.message.refresh) activeQuery.deadline = nowMillis() + 10_000
        else check(nowMillis() < activeQuery.deadline) { "chat_actions_timeout" }
    }

    fun syncRequest(): SyncRequest {
        dropChangedScope()
        val active = pending ?: return SyncRequest()
        active.querying = true
        return SyncRequest(listOf(active.message.request_id))
    }

    fun receiveStatus(frame: RequestStatus) {
        val active = pending ?: return
        val request = active.message
        if (frame.request_id != request.request_id || frame.server_epoch != request.server_epoch || frame.conversation_id != request.conversation_id) return
        if (active.terminal != null) return
        when (frame.state) {
            "reserved", "accepted" -> { active.state = frame.state; active.querying = false }
            "completed", "failed", "rejected", "unknown" -> {
                active.terminal = frame.state; active.code = frame.code; active.querying = false
                needsRefresh = true; query = null; retryAfter = 0
            }
        }
    }

    private fun inputError(text: String): String? {
        if (text.all { it.isWhitespace() || it == '\u0085' }) return "invalid_message"
        if (text.toByteArray(Charsets.UTF_8).size > ProtocolCodec.MAX_TEXT_BYTES) return "text_too_large"
        if (Regex("^/(note|diary|obs)(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())) return "unsupported_command"
        return null
    }

    private fun editorCurrent(value: MessageEditor): Boolean = matchesScope(value.serverEpoch, value.conversationId) &&
        current?.conversationRevision == value.revision && latest?.user_message_id == value.targetId

    val view: ChatActionsViewState get() {
        val state = latest
        val busy = pending != null || refreshing
        val available = supported && context != null && synced && state != null && agrees(state) && !busy &&
            current?.processing?.phase == "idle"
        val canEdit = available && state?.edit_allowed == true &&
            current?.messages?.any { it.id == state.user_message_id && it.role == "user" } == true
        val canReroll = available && state?.reroll_allowed == true &&
            current?.messages?.any { it.id == state.assistant_message_id && it.role == "assistant" } == true
        val localReason = when { !supported -> "not_supported"; context == null || !synced || state == null || !agrees(state) -> "syncing"; busy -> "busy"; else -> null }
        val editable = editor?.let { value ->
            val error = if (!editorCurrent(value)) "stale_target" else inputError(value.text)
            value.copy(canSubmit = canEdit && error == null, reason = error ?: if (!canEdit) localReason ?: state?.edit_reason else null)
        }
        return ChatActionsViewState(supported, state?.user_message_id, state?.assistant_message_id,
            canEdit, canReroll, localReason ?: state?.edit_reason ?: "syncing", localReason ?: state?.reroll_reason ?: "syncing",
            busy, if (refreshing) "confirming" else pending?.state, notice, editable)
    }

    fun openEditor(targetId: String) {
        if (!view.canEdit || latest?.user_message_id != targetId) return
        val snapshot = current ?: return
        val message = snapshot.messages.firstOrNull { it.id == targetId && it.role == "user" } ?: return
        if (message.text.toByteArray(Charsets.UTF_8).size > ProtocolCodec.MAX_TEXT_BYTES) return
        val old = editor?.takeIf { it.targetId == targetId && matchesScope(it.serverEpoch, it.conversationId) }
        editor = MessageEditor(targetId, snapshot.serverEpoch, snapshot.conversationId, snapshot.conversationRevision,
            old?.text ?: message.text, message.attachment_unsupported == true)
        notice = null
    }

    fun editText(value: String) {
        if (pending != null) return
        editor = editor?.copy(text = value)
    }

    fun cancelEditor() { editor = null }

    fun reopenEditor() {
        if (pending != null) return
        val retained = editor ?: return
        if (view.canEdit && latest?.user_message_id == retained.targetId && matchesScope(retained.serverEpoch, retained.conversationId)) {
            openEditor(retained.targetId)
        } else editor = retained.copy(open = true)
    }

    fun createEdit(): ChatAction? {
        val value = view.editor ?: return null
        if (!value.canSubmit) return null
        return create("edit", value.targetId, value.text).also { if (it != null) editor = editor?.copy(open = false) }
    }

    fun createReroll(targetId: String): ChatAction? {
        if (!view.canReroll || latest?.assistant_message_id != targetId) return null
        return create("reroll", targetId, null)
    }

    private fun create(kind: String, targetId: String, text: String?): ChatAction? {
        val connection = context ?: return null
        if (pending != null) return null
        val request = ChatAction(connection.registrationGeneration, connection.serverEpoch, connection.connectionGeneration,
            connection.conversationId, idFactory(), kind, targetId, requireNotNull(current).conversationRevision, text)
        try { ProtocolCodec.encode(request) } catch (_: ProtocolException) { notice = "invalid_message"; return null }
        pending = Pending(request); notice = null
        return request
    }
}
