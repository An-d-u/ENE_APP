package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import dev.ene.companion.storage.DeviceCredentials
import java.util.UUID

/** 메모리 전용 초안과 미확정 요청 한 개. 서버 접수 전까지 초안을 지우지 않는다. */
internal class DraftOutbox {
    private class Pending(val registration: String, val message: SendText, val draftVersion: Long) {
        var accepted = false
        var state = "sending"
        var querying = false
        var unknown = false
    }
    var draft = ""
        private set
    var notice: String? = null
        private set
    private var version = 0L
    private var pending: Pending? = null
    val state: String? get() = pending?.state
    val awaitingResult: Boolean get() = pending?.let { !it.unknown && (it.querying || it.state == "sending") } == true

    private fun registration(credentials: DeviceCredentials) = "${credentials.serverId}/${credentials.deviceId}/${credentials.generation}"
    fun edit(text: String) { if (draft != text) { draft = text; version++ }; notice = null }

    fun create(credentials: DeviceCredentials, session: ConversationSession): SendText {
        check(pending == null)
        val message = SendText(session.serverEpoch, session.conversationId, UUID.randomUUID().toString(), draft)
        ProtocolCodec.encode(message)
        pending = Pending(registration(credentials), message, version)
        notice = null
        return message
    }

    fun syncRequest(credentials: DeviceCredentials, session: ConversationSession): SyncRequest {
        val current = pending ?: return SyncRequest()
        if (current.registration != registration(credentials) || !matches(current, session.serverEpoch, session.conversationId)) {
            pending = null
            notice = "conversation_changed"
            return SyncRequest()
        }
        current.querying = true
        current.unknown = false
        return SyncRequest(listOf(current.message.request_id))
    }

    private fun matches(current: Pending, epoch: String, conversation: String) =
        current.message.server_epoch == epoch && current.message.conversation_id == conversation

    private fun accept(current: Pending) {
        current.accepted = true
        current.state = "accepted"
        if (version == current.draftVersion) { draft = ""; version++ }
    }

    fun status(frame: RequestStatus) {
        val current = pending ?: return
        if (!matches(current, frame.server_epoch, frame.conversation_id) || current.message.request_id != frame.request_id) return
        when (frame.state) {
            "unknown" -> if (current.querying) current.unknown = true
            "reserved" -> if (!current.accepted) { current.state = "reserved"; current.querying = false; current.unknown = false }
            "accepted" -> { accept(current); current.querying = false; current.unknown = false }
            "completed" -> { accept(current); pending = null }
            "failed", "rejected" -> {
                // message_id가 있으면 사용자 메시지는 이미 접수되었다.
                if (frame.message_id != null) accept(current)
                if (frame.state == "rejected" && current.accepted) return
                notice = frame.code ?: "request_failed"
                pending = null
            }
        }
    }

    fun synchronized(snapshot: ConversationSnapshot): SendText? {
        val current = pending ?: return null
        if (!matches(current, snapshot.serverEpoch, snapshot.conversationId)) return null
        if (snapshot.messages.any { it.role == "user" && it.request_id == current.message.request_id }) accept(current)
        if (!current.querying || !current.unknown || current.accepted || snapshot.processing.phase != "idle") return null
        current.querying = false
        current.unknown = false
        current.state = "sending"
        return current.message
    }
}
