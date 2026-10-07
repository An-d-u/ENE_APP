package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import java.util.UUID

data class ThoughtContent(val status: String, val text: String = "") {
    override fun toString() = "ThoughtContent(status=$status)"
}

/** 현재 보이는 답변만 메모리에서 조회한다. 디스크·오디오·캐릭터 상태는 소유하지 않는다. */
internal class ThoughtState(private val ready: Ready, private val nowMillis: () -> Long) {
    private var context: ExtensionContext? = null
    private var snapshot: ConversationSnapshot? = null
    private var visibleIds = emptyList<String>()
    private var pending: ThoughtRequest? = null
    private var pendingSince = 0L
    private var nextAt = 0L
    private var paused = true
    var entries: Map<String, ThoughtContent> = emptyMap()
        private set

    fun visible(ids: List<String>) {
        visibleIds = ids.distinct().take(32)
        entries = entries.filterKeys { it in visibleIds }
    }

    private fun invalidate() { entries = emptyMap(); pending = null }
    fun pause() { paused = true; invalidate() }

    fun synchronize(value: ConversationSnapshot) {
        val old = snapshot
        if (old == null || old.serverEpoch != value.serverEpoch || old.conversationId != value.conversationId ||
            old.conversationRevision != value.conversationRevision) invalidate()
        snapshot = value
        context = context?.copy(serverEpoch = value.serverEpoch, conversationId = value.conversationId)
        paused = false
    }

    fun retry(id: String) {
        if (entries[id]?.status == "error") entries = entries - id
    }

    fun nextRequest(): ThoughtRequest? {
        val current = context ?: return null
        val base = snapshot ?: return null
        if (paused || "message_thoughts_v1" !in ready.capabilities) return null
        val now = nowMillis()
        pending?.let {
            if (now - pendingSince < 10_000) return null
            if (it.message_id in visibleIds) entries = entries + (it.message_id to ThoughtContent("error"))
            pending = null
        }
        if (now < nextAt) return null
        val id = visibleIds.firstOrNull { id -> id !in entries && base.messages.any { it.id == id && it.role == "assistant" } } ?: return null
        nextAt = now + 100
        pendingSince = now
        return ThoughtRequest(current.registrationGeneration, current.serverEpoch, current.connectionGeneration,
            current.conversationId, base.conversationRevision, UUID.randomUUID().toString(), id).also { pending = it }
    }

    fun receive(message: ExtensionMessage) {
        if (message is ExtensionsReady) {
            if (context != null || message.registration_generation != ready.registration_generation || message.server_epoch != ready.server_epoch ||
                message.capabilities.toSet() != ready.capabilities.toSet() || "message_thoughts_v1" !in message.capabilities) return
            context = ExtensionContext(message.registration_generation, message.server_epoch, message.connection_generation, ready.conversation_id)
            return
        }
        if (message !is ThoughtResponse && message !is ThoughtInvalidated) return
        val current = context ?: return
        try { ExtensionCodec.validate(message, current, ready.capabilities.toSet(), "from_pc") }
        catch (_: ProtocolException) { return }
        if (message is ThoughtInvalidated) { invalidate(); return }
        if (paused || message !is ThoughtResponse) return
        val query = pending ?: return
        if (message.query_id != query.query_id || message.message_id != query.message_id ||
            message.conversation_revision != snapshot?.conversationRevision || message.conversation_revision != query.conversation_revision) return
        pending = null
        if (message.message_id !in visibleIds) return
        entries = entries + (message.message_id to if (nowMillis() - pendingSince >= 10_000 || message.status == "stale")
            ThoughtContent("error") else ThoughtContent(message.status, message.text))
    }
}
