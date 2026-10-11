package dev.ene.companion.connection

import dev.ene.companion.protocol.*

data class ChatProgressViewState(val status: String = "syncing", val phase: String? = null, val requestId: String? = null)

/** 현재 연결·완성 대화·요청이 일치하는 생성 상태만 표시한다. 요청 잠금은 소유하지 않는다. */
internal class ChatProgressState(private val nowMillis: () -> Long) {
    private var ready: Ready? = null
    private var context: ExtensionContext? = null
    private var epoch: String? = null
    private var conversation: String? = null
    private var synced = false
    private var processing: ProcessingState? = null
    private var latest: ChatProgress? = null
    private var needed = false
    private var deadline: Long? = null
    private var failed = false
    private var conflictRevision: Long? = null

    val view: ChatProgressViewState get() {
        val current = ready ?: return ChatProgressViewState()
        if ("chat_progress_v1" !in current.capabilities) return ChatProgressViewState("unsupported")
        if (failed) return ChatProgressViewState("error")
        val value = latest
        val matching = value != null && synced && if (value.phase == "idle") processing?.phase == "idle"
            else processing?.phase != "idle" && processing?.request_id == value.request_id
        return if (matching) ChatProgressViewState("ready", value!!.phase, value.request_id) else ChatProgressViewState()
    }

    fun connected(registration: String, value: Ready) {
        // 호출마다 등록·연결 비교 기준을 새로 만든다. 등록별 진행 상태는 영구 보관하지 않는다.
        require(registration.isNotEmpty())
        disconnected()
        ready = value
        epoch = value.server_epoch; conversation = value.conversation_id
        needed = "chat_progress_v1" in value.capabilities
    }

    fun disconnected() {
        ready = null; context = null; epoch = null; conversation = null
        synced = false; processing = null; latest = null
        needed = false; deadline = null; failed = false; conflictRevision = null
    }

    fun forget() = disconnected()

    fun baseState(serverEpoch: String, conversationId: String, synchronized: Boolean, value: ProcessingState? = null) {
        val current = ready ?: return
        if (epoch != serverEpoch || conversation != conversationId) {
            epoch = serverEpoch; conversation = conversationId
            context = context?.copy(serverEpoch = serverEpoch, conversationId = conversationId)
            latest = null; deadline = null; failed = false; conflictRevision = null
            needed = "chat_progress_v1" in current.capabilities
        }
        synced = synchronized
        processing = if (synchronized) value else null
    }

    fun nextRequest(): ChatProgressRequest? {
        if (deadline?.let { nowMillis() >= it } == true) { deadline = null; failed = true }
        val current = context ?: return null
        if (!needed || !synced) return null
        needed = false
        deadline = nowMillis() + 5000
        return ChatProgressRequest(current.registrationGeneration, current.serverEpoch, current.connectionGeneration, current.conversationId)
    }

    fun receive(message: ExtensionMessage) {
        val current = ready ?: return
        if ("chat_progress_v1" !in current.capabilities) return
        if (message is ExtensionsReady) {
            if (context != null || message.registration_generation != current.registration_generation ||
                message.server_epoch != current.server_epoch || message.capabilities.toSet() != current.capabilities.toSet()) return
            val candidate = ExtensionContext(message.registration_generation, message.server_epoch, message.connection_generation, current.conversation_id)
            try { ExtensionCodec.validate(message, candidate, current.capabilities.toSet(), "from_pc") }
            catch (_: ProtocolException) { return }
            context = candidate.copy(serverEpoch = epoch ?: current.server_epoch, conversationId = conversation ?: current.conversation_id)
            return
        }
        if (message !is ChatProgress && !(message is ExtensionError && message.feature == "chat_progress_v1")) return
        val scope = context ?: return
        try { ExtensionCodec.validate(message, scope, current.capabilities.toSet(), "from_pc") }
        catch (_: ProtocolException) { return }
        if (message is ExtensionError) {
            failed = true; deadline = null; needed = false
            return
        }
        message as ChatProgress
        val previous = latest
        if (previous != null && message.progress_revision < previous.progress_revision) return
        if (conflictRevision?.let { message.progress_revision <= it } == true) return
        if (previous != null && previous.progress_revision == message.progress_revision && previous != message) {
            conflictRevision = message.progress_revision
            failed = true; deadline = null
            return
        }
        latest = message
        failed = false; deadline = null; conflictRevision = null
    }
}
