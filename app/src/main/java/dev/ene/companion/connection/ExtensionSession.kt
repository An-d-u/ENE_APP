package dev.ene.companion.connection

import dev.ene.companion.audio.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Main 직렬 경계에서 수신/화면 수명을 결합한다. PCM과 종료 대기는 별도 자식 작업이다. */
internal class ExtensionSession(
    private val ready: Ready,
    parentScope: CoroutineScope,
    private val mediaFactory: (ExtensionContext) -> AudioMedia,
    private val platform: AudioPlatform,
    private val send: (WireMessage) -> Boolean,
    private val nowMillis: () -> Long,
    private val isPublicAssistant: (String) -> Boolean,
    private val onOutput: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    extraCapabilities: Set<String> = emptySet(),
    private val onPlayback: (CharacterPlayback) -> Unit = {},
) {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val serialContext = scope.coroutineContext.minusKey(Job)
    private val capabilities = ExtensionCodec.negotiate(ready.capabilities.toSet(), setOf("audio_pcm_v1") + extraCapabilities)
    private var context: ExtensionContext? = null
    private var conversationId = ready.conversation_id
    private var activeScreen = false
    private var synced = false
    private var closed = false
    private var mode = "auto"
    private var output: String? = null
    private var lastAvailability: Pair<ExtensionContext, Boolean>? = null
    private data class Pending(val offer: AudioOffer, val since: Long, var end: AudioSourceEnd? = null)
    private var pending: Pending? = null
    private class Entry(val ref: AudioRef, val state: AudioSession, val player: PcmPlayer,
                        val focus: AudioFocusController, val media: AudioMedia) {
        var reader: Job? = null
        var totalFrames: Long? = null
        var playedFrames = 0L
    }
    private var active: Entry? = null
    private var retiring: Job? = null
    private val recentIds = ArrayDeque<String>()
    private val timer = scope.launch {
        while (isActive) { delay(50); tick() }
    }

    private fun available() = !closed && context != null && activeScreen && synced && "audio_pcm_v1" in capabilities

    fun resumed(value: Boolean, changingConfigurations: Boolean = false) {
        if (closed) return
        activeScreen = value
        if (!value) {
            pending?.let { reject(it.offer.ref(), "inactive") }; pending = null
            active?.let {
                if (!changingConfigurations || it.state.state != "PLAYING") terminate(it, "inactive")
            }
        }
        availability()
    }

    fun baseState(epoch: String, conversation: String, synchronized: Boolean) {
        if (closed) return
        if (epoch != ready.server_epoch) { failConnection("stale_extension"); return }
        if (conversation != conversationId) {
            pending = null
            active?.let { terminate(it, "conversation_reset") }
            conversationId = conversation
            context = context?.copy(conversationId = conversation)
        }
        synced = synchronized
        availability()
        startPending()
    }

    fun receive(message: ExtensionMessage) {
        if (closed) return
        if (message is ExtensionsReady) {
            if (context != null) return
            val candidate = ExtensionContext(ready.registration_generation, ready.server_epoch,
                message.connection_generation, conversationId)
            try { ExtensionCodec.validate(message, candidate, capabilities, "from_pc") }
            catch (_: ProtocolException) { return }
            context = candidate
            availability(); publishOutput()
            return
        }
        val current = context ?: return
        val rate = active?.player?.sampleRate ?: pending?.offer?.sample_rate
        try { ExtensionCodec.validate(message, current, capabilities, "from_pc", rate) }
        catch (_: ProtocolException) { return }
        if (message is AudioStatus) {
            mode = message.mode
            if (mode != "auto") {
                pending?.let { reject(it.offer.ref(), "output_disabled") }; pending = null
                active?.let { terminate(it, "output_disabled") }
            }
            publishOutput()
            return
        }
        if (message is AudioOffer) {
            if (message.utterance_id in recentIds) return
            recentIds.addLast(message.utterance_id)
            if (recentIds.size > 256) recentIds.removeFirst()
            if (!available() || mode != "auto") { reject(message.ref(), "not_ready"); return }
            if (active != null || pending != null || retiring?.isCompleted == false) {
                reject(message.ref(), "audio_busy"); return
            }
            pending = Pending(message, nowMillis())
            startPending()
            return
        }
        val ref = message.refOrNull() ?: return
        val waiting = pending
        if (waiting != null && ref == waiting.offer.ref()) {
            when (message) {
                is AudioSourceEnd -> {
                    if (waiting.end != null && waiting.end?.total_frames != message.total_frames) {
                        pending = null; reject(ref, "frame_mismatch")
                    } else waiting.end = message
                }
                is AudioCancel -> pending = null
                else -> Unit
            }
            return
        }
        val entry = active?.takeIf { it.ref == ref } ?: return
        when (message) {
            is AudioStart -> {
                if (!activeScreen || !entry.focus.granted) { terminate(entry, "inactive"); return }
                when (entry.state.start(ref, current, nowMillis())) {
                    "play" -> try {
                        if (!entry.player.start()) { terminate(entry, "audio_start_failed"); return }
                        emit(ref.wire("audio_started") { put("played_frames", 0) })
                        characterPlayback(entry, 0.0, true)
                        publishOutput()
                    } catch (_: Exception) { terminate(entry, "audio_start_failed") }
                    "send_cancel" -> terminate(entry, "start_timeout", "audio_cancel")
                }
            }
            is AudioSourceEnd -> {
                if (entry.totalFrames != null && entry.totalFrames != message.total_frames) {
                    terminate(entry, "frame_mismatch"); return
                }
                entry.totalFrames = message.total_frames
                service(entry)
            }
            is AudioProgressAck -> {
                if (entry.state.acknowledge(ref, current, message.played_frames, nowMillis()) == "send_cancel") {
                    terminate(entry, "playback_timeout", "audio_cancel")
                }
            }
            is AudioCancel -> terminate(entry, "pc_cancelled", notify = null)
            else -> Unit
        }
    }

    private fun startPending() {
        val waiting = pending ?: return
        if (!available() || mode != "auto" || !isPublicAssistant(waiting.offer.message_id) || retiring?.isCompleted == false) return
        if (nowMillis() - waiting.since >= 2000) {
            pending = null; reject(waiting.offer.ref(), "message_not_ready"); return
        }
        pending = null
        val offer = waiting.offer
        val ref = offer.ref()
        var player: PcmPlayer? = null
        var focus: AudioFocusController? = null
        var media: AudioMedia? = null
        try {
            player = platform.player(offer.sample_rate, offer.channels)
            focus = platform.focus { reason -> active?.takeIf { it.ref == ref }?.let { terminate(it, reason) } }
            media = mediaFactory(requireNotNull(context))
            val entry = Entry(ref, AudioSession(ref, offer.sample_rate, waiting.since), player, focus, media)
            entry.totalFrames = waiting.end?.total_frames
            active = entry
            entry.reader = scope.launch(start = CoroutineStart.LAZY) { download(entry) }
            entry.reader?.start()
        } catch (_: Exception) {
            runCatching { player?.close() }; runCatching { focus?.close() }; runCatching { media?.close() }
            reject(ref, "audio_initialization_failed")
        }
    }

    private suspend fun download(entry: Entry) {
        try {
            val frames = entry.media.readAudio(entry.ref, entry.player.sampleRate, entry.player.channels) { slice ->
                var offset = slice.offset
                val end = offset + slice.length
                while (offset < end) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(end - offset, entry.player.maxChunkBytes)
                    val accepted = withContext(serialContext) {
                        if (active !== entry || closed) throw CancellationException()
                        when (entry.player.offer(slice.bytes, offset, count)) {
                            "accepted" -> { service(entry); true }
                            "full" -> false
                            else -> throw ConnectionException("invalid_audio")
                        }
                    }
                    if (accepted) offset += count else delay(10)
                }
            }
            if (active === entry && !closed) {
                if (frames != entry.player.receivedFrames) throw ConnectionException("invalid_audio")
                entry.player.httpEof()
                service(entry)
            }
        } catch (_: CancellationException) {
            // 연결 소유자가 이미 동기적으로 정지시켰으며 별도 작업이 join/release한다.
        } catch (_: Exception) { if (active === entry) terminate(entry, "audio_download_failed") }
    }

    private fun tick() {
        if (closed) return
        pending?.let {
            if (nowMillis() - it.since >= 2000) { pending = null; reject(it.offer.ref(), "message_not_ready") }
            else startPending()
        }
        active?.let(::service)
    }

    private fun service(entry: Entry) {
        if (active !== entry || closed) return
        try {
            when (entry.state.tick(nowMillis())) {
                "send_rejected" -> { terminate(entry, "prepare_timeout", "audio_rejected"); return }
                "send_cancel" -> { terminate(entry, "playback_timeout", "audio_cancel"); return }
            }
            val sample = entry.player.pump()
            entry.playedFrames = sample.playedFrames
            val total = entry.totalFrames
            if (total != null && (entry.player.receivedFrames > total ||
                    (entry.player.sourceEnded && entry.player.receivedFrames != total))) {
                terminate(entry, "frame_mismatch"); return
            }
            if (entry.state.state == "PREPARING" && entry.player.ready) {
                val granted = activeScreen && entry.focus.acquire()
                if (active !== entry || closed) return
                when (entry.state.prepared(nowMillis(), entry.player.bufferedFrames, entry.player.sourceEnded, granted)) {
                    "send_prepared" -> emit(entry.ref.wire("audio_prepared") { put("buffered_frames", entry.player.bufferedFrames) })
                    "send_rejected" -> { terminate(entry, "focus_denied", "audio_rejected"); return }
                }
            }
            if (active !== entry || entry.state.state != "PLAYING") return
            characterPlayback(entry, sample.mouthOpen.toDouble(), true)
            when (entry.state.finishIfDrained(sample.playedFrames, entry.player.receivedFrames, total, entry.player.sourceEnded)) {
                "send_finished" -> {
                    emit(entry.ref.wire("audio_finished") { put("played_frames", sample.playedFrames) })
                    terminate(entry, "finished", notify = null); return
                }
                "send_cancel" -> { terminate(entry, "frame_mismatch", "audio_cancel"); return }
            }
            when (entry.state.progress(nowMillis(), sample.playedFrames)) {
                "send_progress" -> emit(entry.ref.wire("audio_progress") {
                    put("played_frames", sample.playedFrames); put("mouth_open", sample.mouthOpen.toDouble())
                })
                "send_cancel" -> terminate(entry, "playback_timeout", "audio_cancel")
            }
        } catch (_: Exception) { if (active === entry) terminate(entry, "audio_output_failed") }
    }

    private fun terminate(entry: Entry, reason: String, notify: String? = "auto") {
        if (active !== entry) return
        val kind = if (notify == "auto") {
            if (entry.state.state == "PREPARING") "audio_rejected" else "audio_cancel"
        } else notify
        active = null
        characterPlayback(entry, 0.0, false)
        entry.state.deactivate()
        entry.player.stop()
        entry.focus.close()
        runCatching { entry.media.close() }
        entry.reader?.cancel()
        retiring = scope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            try { entry.reader?.join() } finally { entry.player.close() }
        }.also { it.start() }
        if (kind != null && !closed) emit(entry.ref.wire(kind) { put("reason", reason) })
        publishOutput()
    }

    private fun reject(ref: AudioRef, reason: String) = emit(ref.wire("audio_rejected") { put("reason", reason) })
    private fun characterPlayback(entry: Entry, mouth: Double, active: Boolean) {
        val ref = entry.ref
        // 화면 오류가 실제 음성 재생을 중단시키지 않도록 표시 콜백은 별도 실패 경계에 둔다.
        runCatching { onPlayback(CharacterPlayback(ref.registrationGeneration, ref.serverEpoch, ref.connectionGeneration,
            ref.conversationId, ref.messageId, ref.utteranceId, "phone",
            entry.playedFrames * 1000 / entry.player.sampleRate, mouth, active)) }
    }
    private fun emit(message: WireMessage) {
        if (closed) return
        if (!runCatching { send(message) }.getOrDefault(false)) failConnection("connection_closed")
    }
    private fun availability() {
        val current = context ?: return
        val value = available()
        if (lastAvailability == current to value) return
        lastAvailability = current to value
        emit(AudioAvailability(current.registrationGeneration, current.serverEpoch, current.connectionGeneration,
            value, current.conversationId, if (value) "ready" else if (!activeScreen) "inactive" else "syncing"))
    }
    private fun publishOutput() {
        val value = if (active?.state?.state == "PLAYING") "phone" else if (mode == "pc_only") "unsupported" else "pc"
        if (output != value) { output = value; onOutput(value) }
    }
    private fun failConnection(reason: String) {
        if (closed) return
        shutdown()
        onFailure(reason)
    }

    fun shutdown() {
        if (closed) return
        closed = true
        pending = null
        timer.cancel()
        active?.let { terminate(it, "connection_closed", notify = null) }
        publishOutput()
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        shutdown()
        timer.join()
        retiring?.join()
        ownerJob.cancelAndJoin()
    }
}

private fun AudioOffer.ref() = AudioRef(registration_generation, server_epoch, connection_generation,
    conversation_id, message_id, operation_id, utterance_id)
private fun ExtensionMessage.refOrNull(): AudioRef? = when (this) {
    is AudioStart -> AudioRef(registration_generation, server_epoch, connection_generation, conversation_id, message_id, operation_id, utterance_id)
    is AudioSourceEnd -> AudioRef(registration_generation, server_epoch, connection_generation, conversation_id, message_id, operation_id, utterance_id)
    is AudioProgressAck -> AudioRef(registration_generation, server_epoch, connection_generation, conversation_id, message_id, operation_id, utterance_id)
    is AudioCancel -> AudioRef(registration_generation, server_epoch, connection_generation, conversation_id, message_id, operation_id, utterance_id)
    else -> null
}
private fun AudioRef.wire(kind: String, extra: JsonObjectBuilder.() -> Unit): WireMessage = ProtocolCodec.decode(buildJsonObject {
    put("type", kind); put("protocol_version", 1)
    put("registration_generation", registrationGeneration); put("server_epoch", serverEpoch)
    put("connection_generation", connectionGeneration); put("conversation_id", conversationId)
    put("message_id", messageId); put("operation_id", operationId); put("utterance_id", utteranceId)
    extra()
}.toString())
