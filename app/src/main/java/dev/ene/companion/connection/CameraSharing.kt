package dev.ene.companion.connection

import dev.ene.companion.input.InputSession
import dev.ene.companion.input.sha256
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraShareView(
    val supported: Boolean = false, val available: Boolean = false,
    val model: String = "", val remote: Boolean = false,
    val generation: Long = 0, val lens: String = "back", val question: Boolean = false,
    val observe: Boolean = false, val observeAllowed: Boolean = false, val observeEpoch: Long = 0,
)

/** 현재 인증 연결과 렌즈에만 유효한 동의. 사진 초안·재전송 큐에 카메라 원본을 넣지 않는다. */
internal class CameraSharing(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CameraShareView())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var acknowledged = false
    private var input: InputSession? = null
    private var send: ((WireMessage) -> Boolean)? = null
    private var camera: (suspend () -> ByteArray?)? = null
    private var pendingCapture: Deferred<InputImageRef?>? = null
    private var observation: Job? = null
    val preparingQuestion get() = pendingCapture != null

    fun connect(session: InputSession, sender: (WireMessage) -> Boolean) {
        disconnect()
        input = session
        send = sender
        share(CameraShareView(supported = true))
    }

    fun disconnect() {
        input = null
        send = null
        invalidate()
        mutable.value = CameraShareView(generation = generation)
    }

    private fun invalidate() {
        acknowledged = false
        camera = null
        pendingCapture?.cancel()
        observation?.cancel()
    }

    private fun share(next: CameraShareView) {
        val c = input?.context ?: return
        invalidate()
        val fresh = next.copy(generation = ++generation, observeAllowed = false)
        mutable.value = fresh
        if (send?.invoke(CameraShare(c.registrationGeneration, c.serverEpoch, c.connectionGeneration,
                c.conversationId, fresh.generation, fresh.lens, fresh.question, fresh.observe)) != true) disconnect()
    }

    fun configure(question: Boolean, observe: Boolean, lens: String) {
        val s = state.value
        if (!s.supported || (!s.available && (question || observe)) || lens !in setOf("back", "front")) return
        if (s.question != question || s.observe != observe || s.lens != lens)
            share(s.copy(lens = lens, question = question, observe = observe))
    }

    fun stop() = configure(false, false, state.value.lens)

    fun receive(frame: CameraState) {
        val c = input?.context ?: return
        val s = state.value
        if (frame.registration_generation != c.registrationGeneration || frame.server_epoch != c.serverEpoch ||
            frame.connection_generation != c.connectionGeneration || frame.conversation_id != c.conversationId ||
            frame.generation != s.generation) return
        val permitted = frame.available && s.observe && frame.observe_allowed
        val next = s.copy(available = frame.available, model = frame.model, remote = frame.remote,
            observeAllowed = permitted, observeEpoch = if (s.observeAllowed != permitted) s.observeEpoch + 1 else s.observeEpoch)
        if ((s.question || s.observe) && (!next.available || next.model != s.model || next.remote != s.remote)) {
            share(next.copy(question = false, observe = false))
            return
        }
        acknowledged = true
        if (!next.observeAllowed) observation?.cancel()
        mutable.value = next
    }

    fun install(epoch: Long, source: (suspend () -> ByteArray?)?) {
        if (epoch == state.value.generation && state.value.question) camera = source
    }

    private suspend fun upload(session: InputSession, bytes: ByteArray, epoch: Long, allowed: () -> Boolean): InputImageRef? {
        if (!allowed() || bytes.size !in 1..InputCodec.CAMERA_BYTES) return null
        val slot = session.upload("camera", bytes, epoch, allowed)
        return InputImageRef(slot.upload_id, bytes.sha256(), bytes.size.toLong(), "image/jpeg")
    }

    /** 질의 접수 직후에만 사진을 만든다. 취소·실패 때는 같은 질문을 텍스트로 돌려준다. */
    fun question(text: SendText, current: () -> Boolean, deliver: (WireMessage, Boolean) -> Unit, abandon: () -> Unit): Boolean {
        val s = state.value
        if (!s.question || text.text.trimStart().startsWith("/")) return false
        val session = input ?: return false
        val c = session.context
        val source = camera
        observation?.cancel()
        fun allowed() = input === session && current() && acknowledged && state.value.generation == s.generation && state.value.question
        val capture = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val bytes = if (allowed()) withTimeoutOrNull(1500) { source?.invoke() } else null
                bytes?.let { upload(session, it, s.generation, ::allowed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
        pendingCapture = capture
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var ref: InputImageRef? = null
            try {
                ref = try { capture.await() } catch (_: CancellationException) { null }
                if (session !== input || !current()) { abandon(); return@launch }
                val image = ref
                if (image != null && allowed()) {
                    deliver(CameraQuestion(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId,
                        s.generation, text.request_id, text.text, image), false)
                    session.consumed(listOf(image.upload_id))
                } else deliver(text, true)
            } finally {
                ref?.let { session.cancelUpload(it.upload_id) }
                if (pendingCapture === capture) pendingCapture = null
            }
        }
        return true
    }

    fun observe(epoch: Long, observeEpoch: Long, jpeg: ByteArray, current: () -> Boolean) {
        val session = input ?: return
        fun allowed() = input === session && current() && acknowledged && state.value.let {
            it.generation == epoch && it.observeEpoch == observeEpoch && it.observeAllowed
        }
        if (!allowed() || preparingQuestion || observation?.isActive == true) return
        observation = scope.launch {
            var ref: InputImageRef? = null
            try {
                val frame = upload(session, jpeg, epoch, ::allowed) ?: return@launch
                ref = frame
                val c = session.context
                if (allowed() && send?.invoke(CameraObservation(c.registrationGeneration, c.serverEpoch,
                        c.connectionGeneration, c.conversationId, epoch, frame)) == true)
                    session.consumed(listOf(frame.upload_id))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* 자동 관찰 실패는 사용자 질문에 영향을 주지 않는다. */ }
            finally { ref?.let { session.cancelUpload(it.upload_id) } }
        }
    }
}
