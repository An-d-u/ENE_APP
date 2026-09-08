package dev.ene.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 데이터 객체를 로그에 실수로 넣어도 대화나 토큰을 출력하지 않는다. */
@Serializable
sealed class WireMessage {
    val protocol_version: Int = 1
    final override fun toString(): String = javaClass.simpleName
}

@Serializable @SerialName("hello")
data class Hello(val capabilities: List<String> = emptyList()) : WireMessage()

@Serializable @SerialName("ready")
data class Ready(val server_id: String, val server_epoch: String, val conversation_id: String, val registration_generation: Long, val capabilities: List<String> = emptyList()) : WireMessage()

@Serializable @SerialName("sync_request")
data class SyncRequest(val pending_request_ids: List<String> = emptyList()) : WireMessage()

@Serializable @SerialName("pair_request")
data class PairRequest(val pairing_id: String, val secret: String, val device_name: String) : WireMessage()

@Serializable @SerialName("pair_pending")
data class PairPending(val pairing_id: String) : WireMessage()

@Serializable @SerialName("pair_failed")
data class PairFailed(val pairing_id: String, val code: String) : WireMessage()

@Serializable @SerialName("pair_approved")
data class PairApproved(val pairing_id: String, val server_id: String, val device_id: String, val registration_generation: Long, val token: String) : WireMessage()

@Serializable @SerialName("snapshot_begin")
data class SnapshotBegin(val server_epoch: String, val conversation_id: String, val snapshot_id: String, val event_seq: Long, val conversation_revision: Long, val part_count: Int, val byte_count: Int, val message_count: Int, val sha256: String) : WireMessage()

@Serializable @SerialName("snapshot_part")
data class SnapshotPart(val server_epoch: String, val conversation_id: String, val snapshot_id: String, val index: Int, val data_base64: String) : WireMessage()

@Serializable @SerialName("snapshot_end")
data class SnapshotEnd(val server_epoch: String, val conversation_id: String, val snapshot_id: String, val part_count: Int, val byte_count: Int, val sha256: String) : WireMessage()

@Serializable @SerialName("event")
data class ChatEvent(val server_epoch: String, val conversation_id: String, val event_seq: Long, val conversation_revision: Long, val op: String, val payload: kotlinx.serialization.json.JsonObject) : WireMessage()

@Serializable @SerialName("resync_required")
data class ResyncRequired(val server_epoch: String, val conversation_id: String, val reason: String) : WireMessage()

@Serializable @SerialName("send_text")
data class SendText(val server_epoch: String, val conversation_id: String, val request_id: String, val text: String) : WireMessage()

@Serializable @SerialName("request_status")
data class RequestStatus(val server_epoch: String, val conversation_id: String, val request_id: String, val state: String, val message_id: String? = null, val code: String? = null) : WireMessage()

@Serializable @SerialName("ping")
data class Ping(val nonce: String) : WireMessage()

@Serializable @SerialName("pong")
data class Pong(val nonce: String) : WireMessage()

@Serializable @SerialName("error")
data class ErrorMessage(val code: String, val request_id: String? = null) : WireMessage()

@Serializable
data class PublicMessage(val id: String, val role: String, val text: String, val displayed_at: String, val request_id: String? = null, val attachment_unsupported: Boolean? = null) {
    override fun toString(): String = "PublicMessage(id=$id, role=$role)"
}

@Serializable
data class ProcessingState(val phase: String, val request_id: String? = null)

data class ConversationSnapshot(val serverEpoch: String, val conversationId: String, val snapshotId: String, val eventSeq: Long, val conversationRevision: Long, val messages: List<PublicMessage>, val processing: ProcessingState) {
    override fun toString(): String = "ConversationSnapshot(messageCount=${messages.size})"
}
