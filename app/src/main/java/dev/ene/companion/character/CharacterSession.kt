package dev.ene.companion.character

import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** 현재 연결의 캐릭터만 소유한다. Main 직렬 상태와 IO 취소용 원자 플래그를 분리한다. */
class CharacterSession(
    private val ready: Ready,
    private val identity: String,
    parentScope: CoroutineScope,
    private val platform: CharacterPlatform,
    private val mediaFactory: (ExtensionContext, () -> Boolean) -> CharacterMedia,
    private val send: (WireMessage) -> Boolean,
    private val nowMillis: () -> Long,
    private val isPublicAssistant: (String) -> Boolean,
    private val onState: (CharacterViewState) -> Unit,
) {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val capabilities = ready.capabilities.toSet()
    private val sequence = CharacterSequence()
    private val playback = CharacterPlaybackClock()
    private var context: ExtensionContext? = null
    private var conversation = ready.conversation_id
    private var synced = false
    private var activeScreen = false
    private var closed = false
    private var failed = false
    private var renderer: CharacterRenderer? = null
    private var currentLoad: CharacterLoad? = null
    private var worker: Job? = null
    private var download: Job? = null
    private var media: CharacterMedia? = null
    private var token: AtomicBoolean? = null
    private var pending = false
    private var missedExpression = 0L
    private var lastMouth: CharacterMouth? = null
    private var state = CharacterViewState()
    private var headPat: HeadPatSession? = null
    private var settings: CharacterSettingsState? = null
    private val timer = scope.launch { while (isActive) { delay(50); publishMouth(); headPat?.tick(); settings?.tick(); publishSettings() } }

    private fun available() = !closed && !failed && context != null && activeScreen && synced &&
        platform.supported && "character_v1" in capabilities

    fun resumed(value: Boolean, changingConfigurations: Boolean = false) {
        if (closed) return
        activeScreen = value
        if (!value) {
            settings?.available(false); publishSettings()
            headPat?.cancel()
            sequence.detached()
            if (!changingConfigurations) {
                token?.set(false); closeMedia(); download?.cancel(); pending = false
                publish("paused")
            }
        } else if (available()) {
            if (currentLoad == null || state.status == "paused" || settings?.view?.busy == true) requestLoad() else display()
        }
    }

    fun baseState(epoch: String, conversation: String, synchronized: Boolean) {
        if (closed || epoch != ready.server_epoch) return
        if (this.conversation != conversation) {
            settings?.close(); publishSettings()
            headPat?.cancel()
            this.conversation = conversation
            context = context?.copy(conversationId = conversation)
            playback.reset(); lastMouth = null
        }
        val becameReady = !synced && synchronized
        synced = synchronized
        if (!synchronized) { sequence.detached(); headPat?.cancel(); settings?.available(false); publishSettings() }
        if (becameReady && available()) {
            if (currentLoad == null || settings?.view?.busy == true) requestLoad() else display()
        }
    }

    fun receive(message: ExtensionMessage) {
        if (closed) return
        if (message is ExtensionsReady) {
            if (context != null || !platform.supported || "character_v1" !in capabilities) return
            val candidate = ExtensionContext(ready.registration_generation, ready.server_epoch, message.connection_generation, conversation)
            try { ExtensionCodec.validate(message, candidate, capabilities, "from_pc") } catch (_: ProtocolException) { return }
            context = candidate
            if ("character_controls_v1" in capabilities) {
                headPat = HeadPatSession(candidate, send = { send(it) }, visual = { value -> render { it.post("head_pat", wire(value)) } }, nowMillis = nowMillis)
                settings = CharacterSettingsState(candidate, send = { send(it) },
                    showPreview = { snapshot -> if (available()) render { it.post("preview", snapshot.json) } },
                    requestSnapshot = { if (available()) requestLoad() }, nowMillis = nowMillis)
            }
            if (available()) requestLoad()
            return
        }
        val current = context ?: return
        try { ExtensionCodec.validate(message, current, capabilities, "from_pc") } catch (_: ProtocolException) { return }
        when (message) {
            is CharacterSettingsResult -> { settings?.receive(message); publishSettings() }
            is HeadPatState -> headPat?.receive(message, available() && state.status == "ready")
            is CharacterChanged -> if (sequence.changed(message.state_revision, message.model_version) && available()) requestLoad()
            is CharacterAction -> {
                val before = sequence.observedAction
                when (sequence.action(message)) {
                    "apply" -> render { it.post("action", wire(message)) }
                    "refresh" -> {
                        runCatching { send(CharacterSnapshotRequest(current.registrationGeneration, current.serverEpoch, current.connectionGeneration)) }
                        if (available()) requestLoad()
                    }
                    else -> if (message.kind == "expression" && message.action_seq > before) missedExpression = maxOf(missedExpression, message.action_seq)
                }
            }
            is CharacterPlayback -> if (isPublicAssistant(message.message_id)) { playback.remote(message, nowMillis()); publishMouth() }
            is ExtensionError -> if (message.feature == "character_v1") fail("character_unavailable")
            else -> Unit
        }
    }

    fun localPlayback(message: CharacterPlayback) {
        val current = context ?: return
        if (closed || !isPublicAssistant(message.message_id)) return
        try { ExtensionCodec.validate(message, current, capabilities, "from_pc") } catch (_: ProtocolException) { return }
        playback.local(message, nowMillis()); publishMouth()
    }

    fun attach(value: CharacterRenderer) {
        if (closed) return
        if (renderer !== value) { headPat?.cancel(); settings?.close() }
        renderer = value; sequence.detached(); lastMouth = null
        if (available()) display()
    }

    fun detach(value: CharacterRenderer) {
        if (renderer !== value) return
        settings?.close(); publishSettings()
        headPat?.cancel()
        renderer = null; sequence.detached(); lastMouth = null
    }

    fun event(source: CharacterRenderer, event: CharacterEvent) {
        if (closed || renderer !== source) return
        if (event.type == "head_pat_input") {
            val input = event.input ?: return
            if (available() && state.status == "ready" && headPat != null) headPat?.input(input)
            else render { it.post("head_pat", buildJsonObject {
                put("model_version", input.modelVersion); put("interaction_id", input.interactionId)
                put("source", "phone"); put("phase", "rejected")
            }) }
            return
        }
        if (!available()) return
        when (event.type) {
            "ready" -> {
                val version = event.modelVersion ?: return
                if (version != sequence.snapshot?.modelVersion || pending || download?.isActive == true) return
                sequence.rendered(version)
                publish("ready"); publishMouth(force = true)
                if (missedExpression > (sequence.snapshot?.actionSeq ?: 0)) { missedExpression = 0; requestLoad() }
            }
            "unavailable", "error" -> fail("character_render_failed")
        }
    }

    fun rendererFailed(source: CharacterRenderer, code: String) {
        if (renderer === source) fail(if (code == "character_renderer_gone") code else "character_render_failed")
    }

    fun retry() {
        if (closed) return
        failed = false
        state = state.copy(viewGeneration = state.viewGeneration + 1)
        if (available()) requestLoad()
    }

    private fun requestLoad() {
        if (!available()) return
        pending = true; sequence.loading()
        token?.set(false); closeMedia(); download?.cancel()
        publish(if (currentLoad?.snapshot?.modelVersion == sequence.snapshot?.modelVersion && renderer != null && currentLoad != null) "refreshing" else "loading")
        if (worker?.isActive == true) return
        worker = scope.launch(start = CoroutineStart.LAZY) {
            while (pending && available()) {
                delay(150)
                if (!available()) break
                pending = false
                val current = context ?: break
                val validity = AtomicBoolean(true).also { token = it }
                download = scope.launch(start = CoroutineStart.LAZY) {
                    var loaded: CharacterLoad? = null
                    try {
                        val transport = mediaFactory(current, validity::get).also { media = it }
                        loaded = platform.load(identity, transport, validity::get)
                        currentCoroutineContext().ensureActive()
                        if (!validity.get()) return@launch
                        if (!sequence.install(loaded.snapshot)) { fail("character_state_stale"); return@launch }
                        currentLoad?.close()
                        currentLoad = loaded; loaded = null
                        sequence.snapshot?.let { settings?.install(it) }
                        if (available()) display()
                    } catch (_: CancellationException) {
                        // 최신 대기 요청 하나가 앞 작업의 회수 후 실행된다.
                    } catch (_: Exception) { if (validity.get() && !closed) fail("character_download_failed") }
                    finally { loaded?.close(); closeMedia(); media = null }
                }.also { it.start() }
                download?.join(); download = null
                if (!pending && available() && missedExpression > (sequence.snapshot?.actionSeq ?: 0)) {
                    missedExpression = 0; pending = true; sequence.loading()
                }
            }
        }.also { it.start() }
    }

    private fun display() {
        val loaded = currentLoad ?: return
        if (!available()) return
        val snapshot = sequence.snapshot ?: loaded.snapshot
        publish(if (snapshot.status == "ready") "rendering" else snapshot.status)
        render { it.show(snapshot, loaded.character) }
    }

    private fun publishMouth(force: Boolean = false) {
        if (closed || !available()) return
        val mouth = playback.current(nowMillis())
        if (force || mouth != lastMouth) {
            render { it.post("playback", mouth.json()) }
            lastMouth = mouth
        }
    }

    private fun publish(status: String, error: String? = null) {
        settings?.available(available() && sequence.snapshot?.status == "ready")
        val next = state.copy(status = status, errorCode = error,
            settings = settingsView(status))
        if (next != state) { state = next; onState(next) }
        val snapshot = sequence.snapshot
        val enabled = snapshot?.json?.get("settings")?.jsonObject?.get("enable_head_pat")?.jsonPrimitive?.booleanOrNull ?: true
        headPat?.configure(snapshot?.modelVersion, available() && status == "ready" && enabled)
    }

    private fun settingsView(status: String = state.status): CharacterSettingsViewState {
        val value = settings?.view ?: return CharacterSettingsViewState()
        // 큰 글자/키보드로 표시부를 접어도 확인된 모델의 설정까지 막지는 않는다.
        val readyToEdit = status == "ready" || (status == "rendering" && renderer == null &&
            currentLoad?.snapshot?.status == "ready" && !pending && download?.isActive != true)
        return value.copy(available = value.available && available() && readyToEdit)
    }

    private fun publishSettings() {
        val value = settingsView()
        if (state.settings != value) { state = state.copy(settings = value); onState(state) }
    }

    fun openSettings() { if (settingsView().available) settings?.open(); publishSettings() }
    fun closeSettings() { settings?.close(); publishSettings() }
    fun previewSettings(key: String, value: JsonElement, parameter: Boolean) {
        if (settingsView().available) settings?.preview(key, value, parameter)
        publishSettings()
    }
    fun submitSettings() { if (settingsView().available) settings?.submit(); publishSettings() }

    private fun render(block: (CharacterRenderer) -> Unit) {
        val target = renderer ?: return
        try { block(target) } catch (_: Exception) { fail("character_render_failed") }
    }

    private fun closeMedia() { runCatching { media?.close() } }

    private fun fail(code: String) {
        if (closed || failed) return
        failed = true; pending = false; token?.set(false); closeMedia(); download?.cancel()
        headPat?.cancel()
        sequence.detached(); playback.reset()
        publish("error", code)
    }

    fun shutdown() {
        if (closed) return
        headPat?.cancel()
        closed = true; pending = false; token?.set(false); closeMedia()
        download?.cancel(); worker?.cancel(); timer.cancel()
        runCatching { renderer?.clear() }
        currentLoad?.close(); currentLoad = null; renderer = null
        sequence.detached(); playback.reset(); publish("unavailable")
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        shutdown(); worker?.join(); download?.join(); timer.join(); ownerJob.cancelAndJoin()
    }

    private fun wire(message: ExtensionMessage) = Json.parseToJsonElement(ProtocolCodec.encode(message)).jsonObject
}
