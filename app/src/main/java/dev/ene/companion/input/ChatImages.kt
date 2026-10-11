package dev.ene.companion.input

import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import java.util.UUID

class ChatThumbnail(val imageId: String, val bytes: ByteArray) {
    override fun toString() = "ChatThumbnail($imageId)"
}

/** 현재 연결·대화의 공개 사진만 보관한다. 인코딩/디코딩 비용을 합쳐 8 MiB 이하로 제한한다. */
class ChatImages(private val input: InputSession, private val scope: CoroutineScope,
    private val send: (WireMessage) -> Boolean, private val now: () -> Long,
    private val publish: (Map<String, List<ChatThumbnail>>) -> Unit) {
    private val entries = linkedMapOf<String, List<ChatThumbnail>>()
    private val queries = mutableMapOf<String, Triple<String, Long, Long>>()
    private val checked = mutableMapOf<String, Long>()
    private val revisions = mutableMapOf<String, Long>()
    private val loads = mutableMapOf<String, Job>()
    private var snapshot: ConversationSnapshot? = null
    private var visible = emptyList<String>()
    private var closed = false
    fun synchronize(value: ConversationSnapshot, ids: List<String>) {
        if (closed) return
        if (snapshot?.conversationRevision != value.conversationRevision) checked.clear()
        (ids - visible.toSet()).forEach(checked::remove)
        snapshot = value; visible = ids.take(16)
        val public = value.messages.filter { it.role == "user" && it.attachment_unsupported == true }.map { it.id }.toSet()
        (entries.keys + loads.keys).filter { it !in public }.toSet().forEach { invalidate(it) }
        queries.entries.removeAll { now() - it.value.third > 5000 }
        for (id in visible.filter { it in public }) {
            if (queries.values.any { it.first == id } || loads[id]?.isActive == true || id in checked) continue
            val c = input.context
            val query = UUID.randomUUID().toString()
            queries[query] = Triple(id, value.conversationRevision, now())
            checked[id] = now()
            send(ChatImagesRequest(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, query, id, value.conversationRevision))
        }
    }
    private fun invalidate(id: String) {
        loads.remove(id)?.cancel(); entries.remove(id); checked.remove(id); revisions.remove(id)
        queries.entries.removeAll { it.value.first == id }; publish(entries.toMap())
    }
    fun receive(frame: ExtensionMessage) {
        val c = input.context
        if (closed || frame.registration_generation != c.registrationGeneration || frame.server_epoch != c.serverEpoch || frame.connection_generation != c.connectionGeneration) return
        if (frame is ChatImagesInvalidated && frame.conversation_id == c.conversationId) { invalidate(frame.message_id); return }
        if (frame !is ChatImagesState || frame.conversation_id != c.conversationId) return
        val query = queries.remove(frame.query_id) ?: return
        if (query.first != frame.message_id || query.second != frame.conversation_revision || snapshot?.conversationRevision != frame.conversation_revision) return
        if (revisions[frame.message_id] == frame.attachment_revision && entries.containsKey(frame.message_id)) return
        loads.remove(frame.message_id)?.cancel()
        revisions[frame.message_id] = frame.attachment_revision
        entries.remove(frame.message_id); publish(entries.toMap())
        loads[frame.message_id] = scope.launch {
            try {
                val images = frame.images.map { ChatThumbnail(it.id, input.thumbnail(frame.message_id, it.id, frame.attachment_revision)) }
                if (!closed && revisions[frame.message_id] == frame.attachment_revision && snapshot?.messages?.any { it.id == frame.message_id } == true) {
                    // 320×320 ARGB 픽셀과 128 KiB 전송 본문을 항목당 보수적으로 예약한다.
                    while (entries.values.sumOf { it.size } + images.size > 15 && entries.isNotEmpty()) entries.remove(entries.keys.first())
                    entries[frame.message_id] = images
                    publish(entries.toMap())
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* 다음 가시성 확인에서 다시 조회한다. 원본은 가져오지 않는다. */ }
        }
    }
    fun close() { closed = true; loads.values.forEach { it.cancel() }; entries.clear(); queries.clear(); publish(emptyMap()) }
}
