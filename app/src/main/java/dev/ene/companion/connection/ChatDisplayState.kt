package dev.ene.companion.connection

import dev.ene.companion.protocol.*

data class ChatDisplayViewState(val status: String = "syncing", val splitEnabled: Boolean = false)

/** 현재 등록의 표시 설정만 메모리에 보관한다. 대화·미디어 상태와 독립적이다. */
internal class ChatDisplayState(private val nowMillis: () -> Long) {
    private var identity: String? = null
    private var ready: Ready? = null
    private var context: ExtensionContext? = null
    private var revision: Long? = null
    private var needed = false
    private var pending = false
    private var deadline: Long? = null
    var view = ChatDisplayViewState()
        private set

    fun connected(registration: String, value: Ready) {
        if (identity != registration) forget()
        identity = registration
        ready = value
        context = null
        revision = null
        pending = false
        needed = "chat_display_v1" in value.capabilities
        deadline = if (needed) nowMillis() + 5000 else null
        view = if (needed) view.copy(status = "syncing") else ChatDisplayViewState("unsupported", false)
    }

    fun disconnected() {
        ready = null; context = null; revision = null
        needed = false; pending = false; deadline = null
        view = view.copy(status = "syncing")
    }

    fun forget() {
        disconnected()
        identity = null
        view = ChatDisplayViewState()
    }

    fun refresh() {
        if (ready?.capabilities?.contains("chat_display_v1") != true || pending || needed) return
        needed = true
        deadline = nowMillis() + 5000
        view = view.copy(status = "syncing")
    }

    fun nextRequest(): ChatDisplayRequest? {
        if (deadline?.let { nowMillis() >= it } == true) {
            pending = false; needed = false; deadline = null
            view = view.copy(status = "error")
        }
        val current = context ?: return null
        if (!needed || pending) return null
        needed = false; pending = true
        return ChatDisplayRequest(current.registrationGeneration, current.serverEpoch, current.connectionGeneration)
    }

    fun receive(message: ExtensionMessage) {
        val currentReady = ready ?: return
        if ("chat_display_v1" !in currentReady.capabilities) return
        if (message is ExtensionsReady) {
            if (context != null || message.registration_generation != currentReady.registration_generation ||
                message.server_epoch != currentReady.server_epoch ||
                message.capabilities.toSet() != currentReady.capabilities.toSet()) return
            val candidate = ExtensionContext(message.registration_generation, message.server_epoch,
                message.connection_generation, currentReady.conversation_id)
            try { ExtensionCodec.validate(message, candidate, currentReady.capabilities.toSet(), "from_pc") }
            catch (_: ProtocolException) { return }
            context = candidate
            if (!needed) refresh()
            return
        }
        if (message !is ChatDisplaySettings) return
        val current = context ?: return
        try { ExtensionCodec.validate(message, current, currentReady.capabilities.toSet(), "from_pc") }
        catch (_: ProtocolException) { return }
        val previous = revision
        if (previous != null && message.display_revision < previous) return
        pending = false; needed = false; deadline = null
        if (previous == message.display_revision && message.message_split_enabled != view.splitEnabled) {
            view = view.copy(status = "error")
            return
        }
        revision = message.display_revision
        view = ChatDisplayViewState("ready", message.message_split_enabled)
    }
}
