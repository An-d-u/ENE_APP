package dev.ene.companion.input

import dev.ene.companion.protocol.*
import java.util.UUID

/** 접수 여부가 불명확하면 같은 요청 ID를 조회한다. 사진 재업로드는 사용자가 명시적으로 재시도한다. */
class PhotoOutbox {
    class Pending(val registration: String, val context: ExtensionContext, val id: String, val text: String,
                  val version: Long, val photos: List<LocalPhoto>) {
        var accepted = false
        var sent = false
        var querying = false
        var unknown = false
    }
    var pending: Pending? = null
        private set
    val canRetry get() = pending?.let { it.querying && it.unknown && !it.accepted } == true
    fun create(registration: String, context: ExtensionContext, text: String, version: Long, photos: List<LocalPhoto>): Pending {
        check(pending == null && photos.size in 1..4 && text.toByteArray().size <= 16384)
        return Pending(registration, context, UUID.randomUUID().toString(), text, version, photos.toList()).also { pending = it }
    }
    fun sent() { pending?.sent = true }
    fun failedBeforeSend() { if (pending?.sent == false) pending = null }
    fun clear() { pending = null }
    fun sync(registration: String, context: ExtensionContext): SyncRequest {
        val item = pending ?: return SyncRequest()
        if (item.registration != registration || item.context.serverEpoch != context.serverEpoch || item.context.conversationId != context.conversationId) {
            pending = null; return SyncRequest()
        }
        if (!item.sent) { pending = null; return SyncRequest() }
        item.querying = true; item.unknown = false
        return SyncRequest(listOf(item.id))
    }
    fun retry(): Pending {
        check(canRetry)
        return requireNotNull(pending).also { it.querying = false; it.unknown = false; it.sent = false }
    }
    fun status(frame: RequestStatus): Pending? {
        val item = pending ?: return null
        if (frame.request_id != item.id || frame.server_epoch != item.context.serverEpoch || frame.conversation_id != item.context.conversationId) return null
        when (frame.state) {
            "unknown" -> if (item.querying) item.unknown = true
            "reserved" -> { item.querying = false; item.unknown = false }
            "accepted", "completed" -> { item.accepted = true; item.querying = false; item.unknown = false }
            "failed", "rejected" -> { if (frame.message_id != null) item.accepted = true }
        }
        if (frame.state in setOf("completed", "failed", "rejected")) pending = null
        return item.takeIf { it.accepted }
    }
}
