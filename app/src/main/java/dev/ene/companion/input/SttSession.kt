package dev.ene.companion.input

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import java.util.UUID

data class SttView(val phase: String = "idle", val reason: String? = null, val maximumMs: Long = 30000) {
    val busy get() = phase in setOf("starting", "recording", "recorded", "uploading", "transcribing")
}

class SttSession(private val input: InputSession, private val recorder: RecordingPlatform,
    private val scope: CoroutineScope, private val send: (WireMessage) -> Boolean,
    private val publish: (SttView) -> Unit, private val result: (String) -> Unit) {
    private var command: String? = null
    private var ready: CompletableDeferred<SttReady>? = null
    private var job: Job? = null
    private var phase = "idle"
    private var closed = false
    private var completion: CompletableDeferred<Unit>? = null
    private fun show(value: String, reason: String? = null, max: Long = 30000) {
        phase = value; publish(SttView(value, reason, max))
    }
    fun start() {
        if (closed || job?.isActive == true || command != null) return
        val id = UUID.randomUUID().toString().also { command = it }
        val waiting = CompletableDeferred<SttReady>().also { ready = it }
        val completed = CompletableDeferred<Unit>().also { completion = it }
        val c = input.context
        show("starting")
        job = scope.launch {
            try {
                if (!send(SttBegin(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, id))) throw ConnectionException("connection_closed")
                val limits = withTimeout(5000) { waiting.await() }
                if (closed || command != id) return@launch
                show("recording", max = limits.max_ms)
                val pcm = recorder.record((limits.max_ms * 32).toInt())
                try {
                    if (!completed.isCompleted) {
                        show("recorded", max = limits.max_ms)
                        // 최대 길이에서는 마이크만 멈춘다. PC 전송은 명시적인 완료 입력을 기다린다.
                        withTimeout(60000) { completed.await() }
                    }
                    if (pcm.size < limits.min_ms * 32) throw ConnectionException("recording_too_short")
                    if (closed || command != id) return@launch
                    show("uploading")
                    val slot = input.upload("pcm", pcm)
                    if (closed || command != id) return@launch
                    show("transcribing")
                    if (!send(SttTranscribe(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, id,
                            slot.upload_id, pcm.sha256(), pcm.size.toLong()))) throw ConnectionException("connection_closed")
                    input.consumed(listOf(slot.upload_id))
                } finally { pcm.fill(0) }
                delay(125000)
                if (command == id) this@SttSession.cancel("stt_timeout")
            } catch (_: TimeoutCancellationException) {
                if (command == id) this@SttSession.cancel("stt_timeout")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (command == id) this@SttSession.cancel(if (error is ConnectionException) error.code else "stt_failed")
            }
        }
    }
    fun finish() {
        if (phase in setOf("recording", "recorded")) {
            completion?.complete(Unit)
            if (phase == "recording") recorder.finish()
        }
    }
    fun receive(frame: ExtensionMessage) {
        val c = input.context
        if (closed || frame.registration_generation != c.registrationGeneration || frame.server_epoch != c.serverEpoch || frame.connection_generation != c.connectionGeneration) return
        when (frame) {
            is SttReady -> if (frame.command_id == command && frame.conversation_id == c.conversationId) ready?.complete(frame)
            is SttResult -> if (frame.command_id == command && frame.conversation_id == c.conversationId) {
                command = null; job?.cancel(); ready?.cancel(); recorder.cancel()
                if (frame.status == "complete") { show("idle"); result(frame.text) }
                else show("idle", frame.reason)
            }
            is ExtensionError -> if (frame.command_id == command) cancel(frame.code)
            else -> Unit
        }
    }
    fun cancel(reason: String = "recording_cancelled") {
        val id = command ?: return
        command = null; recorder.cancel(); job?.cancel(); ready?.cancel(); input.cancelUploads()
        val c = input.context
        send(SttCancel(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, id))
        show("idle", reason)
    }
    fun close() { cancel(); closed = true }
}
