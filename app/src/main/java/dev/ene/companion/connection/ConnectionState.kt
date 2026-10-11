package dev.ene.companion.connection

import dev.ene.companion.protocol.PublicMessage
import dev.ene.companion.protocol.ProcessingState

/** 외부 응답 원문이나 자격증명을 예외에 담지 않는다. */
class ConnectionException(val code: String, val peerAuthenticated: Boolean = false) : Exception(code)

enum class ConnectionPhase { UNREGISTERED, CONNECTING, AWAITING_APPROVAL, SYNCING, CONNECTED, RECONNECTING, PAUSED, ACTION_REQUIRED }

data class AudioOutputStatus(
    val preference: String? = null,
    val output: String? = null,
    val state: String? = null,
    val reason: String? = null,
)

data class ConversationScope(val registrationId: String, val serverEpoch: String, val conversationId: String)

data class ConnectionViewState(
    val phase: ConnectionPhase = ConnectionPhase.UNREGISTERED,
    val registered: Boolean = false,
    val endpoint: Endpoint? = null,
    val errorCode: String? = null,
    val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    val messages: List<PublicMessage> = emptyList(),
    val processing: ProcessingState = ProcessingState("idle"),
    val draft: String = "",
    val sendState: String? = null,
    val audioOutput: AudioOutputStatus = AudioOutputStatus(),
    val chatActions: ChatActionsViewState = ChatActionsViewState(),
    val thoughts: Map<String, ThoughtContent> = emptyMap(),
    val chatDisplay: ChatDisplayViewState = ChatDisplayViewState(),
    val chatProgress: ChatProgressViewState = ChatProgressViewState(),
    val conversationScope: ConversationScope? = null,
    val conversationRevision: Long? = null,
    val photoCount: Int = 0,
    val photoPreparing: Boolean = false,
    val photoSupported: Boolean = false,
    val photoAllowed: Boolean = false,
    val photoRetry: Boolean = false,
    val inputNotice: String? = null,
    val chatImages: Map<String, List<dev.ene.companion.input.ChatThumbnail>> = emptyMap(),
    val stt: dev.ene.companion.input.SttView = dev.ene.companion.input.SttView(),
    val sttSupported: Boolean = false,
    val sttReview: String? = null,
) {
    val canSend: Boolean get() = phase == ConnectionPhase.CONNECTED && processing.phase == "idle" && sendState == null && !chatActions.busy &&
        !photoPreparing && !stt.busy && (draft.isNotBlank() || photoCount > 0) && (photoCount == 0 || photoSupported && photoAllowed)
    override fun toString(): String = "ConnectionViewState(phase=$phase)"
}
