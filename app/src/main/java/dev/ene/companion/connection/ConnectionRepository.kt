package dev.ene.companion.connection

import dev.ene.companion.pairing.PairingQr
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.*
import dev.ene.companion.audio.AudioPlatform
import dev.ene.companion.character.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 앱 수명에 하나만 둔다. 전환은 직렬화하고 이전 연결/저장 작업 회수 후 다음 연결을 연다. */
class ConnectionRepository(
    private val registrations: RegistrationStorage,
    private val profiles: ConnectionSettingsStorage,
    private val transportFactory: (TrustedServer) -> ConnectionTransport = ::OkHttpTransport,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
    private val audioPlatform: AudioPlatform? = null,
    private val characterPlatform: CharacterPlatform? = null,
    private val placementController: CharacterPlacementController? = null,
    private val discovery: PcDiscovery = NoPcDiscovery,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transition = Mutex()
    private val mutableState = MutableStateFlow(ConnectionViewState())
    val state = mutableState.asStateFlow()
    private val mutableCharacterState = MutableStateFlow(CharacterViewState())
    val characterState = mutableCharacterState.asStateFlow()
    val characterPlacement = placementController?.state ?: MutableStateFlow(CharacterPlacementState(loaded = true)).asStateFlow()
    private var characterPanelVisible = true
    private var characterRenderer: CharacterRenderer? = null
    private var characterViewGeneration = 0L
    private var foreground = false
    private var resumedActivity = false
    private var generation = 0L
    private var connection: Job? = null
    private var drafts = DraftOutbox()
    private class Active(val credentials: DeviceCredentials, val session: ConversationSession, val socket: CompanionSocket) {
        var responseDeadline: Long? = null
        val mediaCurrent = AtomicBoolean(true)
        var extensions: ExtensionSession? = null
        var character: CharacterSession? = null
        var characterLocalGeneration = -1L
    }
    private var active: Active? = null

    init {
        scope.launch { characterPlacement.collect { active?.character?.placement(it) } }
    }
    fun characterPanelVisible(value: Boolean) {
        characterPanelVisible = value
        active?.character?.panelVisible(value)
    }
    fun changeCharacterPlacement(value: CharacterPlacement) { placementController?.change(value) }
    fun finishCharacterPlacement() { placementController?.finishAdjustment() }
    fun resetCharacterPlacement() { placementController?.reset() }
    fun retryCharacterPlacementSave() { placementController?.retrySave() }

    /** Activity의 Main 콜백에서 즉시 호출한다. 느린 저장 작업의 mutex 뒤로 미루지 않는다. */
    fun activityResumed(value: Boolean, changingConfigurations: Boolean = false) {
        resumedActivity = value
        active?.extensions?.resumed(value, changingConfigurations)
        active?.character?.resumed(value, changingConfigurations)
    }

    fun attachCharacter(renderer: CharacterRenderer) {
        characterRenderer = renderer
        active?.character?.attach(renderer)
    }
    fun detachCharacter(renderer: CharacterRenderer) {
        if (characterRenderer !== renderer) return
        active?.character?.detach(renderer)
        characterRenderer = null
    }
    fun characterEvent(renderer: CharacterRenderer, event: CharacterEvent) {
        if (characterRenderer === renderer) active?.character?.event(renderer, event)
    }
    fun characterFailed(renderer: CharacterRenderer, code: String) {
        if (characterRenderer === renderer) active?.character?.rendererFailed(renderer, code)
    }
    fun retryCharacter() { active?.character?.retry() }
    fun openCharacterSettings() { active?.character?.openSettings() }
    fun closeCharacterSettings() { active?.character?.closeSettings() }
    fun previewCharacterSettings(key: String, value: JsonElement, parameter: Boolean) { active?.character?.previewSettings(key, value, parameter) }
    fun submitCharacterSettings() { active?.character?.submitSettings() }

    fun editDraft(text: String): Job = command { drafts.edit(text); publishDraft() }
    fun sendDraft(): Job = command {
        val current = active ?: return@command
        if (!mutableState.value.canSend || current.session.syncing) return@command
        val message = drafts.create(current.credentials, current.session)
        current.responseDeadline = nowMillis() + 10_000
        publishDraft()
        if (!current.socket.send(message)) {
            show(ConnectionPhase.RECONNECTING, "connection_closed")
            current.socket.cancel()
        }
    }

    private fun publishDraft() {
        mutableState.value = mutableState.value.copy(draft = drafts.draft, sendState = drafts.state,
            errorCode = drafts.notice ?: mutableState.value.errorCode)
    }

    fun updateAddress(host: String, port: Int): Job = command {
        val endpoint = Endpoint.parse(host, port)
        val credentials = withContext(ioDispatcher) { registrations.load() } ?: return@command
        stopConnection()
        show(ConnectionPhase.ACTION_REQUIRED)
        withContext(ioDispatcher) { profiles.save(ConnectionProfile(credentials.serverId, listOf(endpoint))) }
        if (foreground) startRegistered()
    }

    fun forget(): Job = command {
        stopConnection()
        drafts = DraftOutbox()
        mutableState.value = ConnectionViewState(phase = ConnectionPhase.ACTION_REQUIRED)
        clearCharacterCache()
        withContext(ioDispatcher) { registrations.clear(); profiles.clear() }
        mutableState.value = ConnectionViewState()
    }

    fun retry(): Job = command {
        if (foreground) { stopConnection(); startRegistered() }
    }

    fun cancelPairing(): Job = command {
        stopConnection()
        if (foreground) startRegistered() else show(ConnectionPhase.PAUSED)
    }

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        transition.withLock {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(errorCode = failureCode(error)) }
        }
    }

    fun foreground(active: Boolean): Job = command {
        if (foreground != active) {
            foreground = active
            stopConnection()
            if (active) startRegistered() else show(ConnectionPhase.PAUSED)
        }
    }

    fun pair(raw: String): Job = command {
        if (!foreground) return@command
        val qr = PairingQr.parse(raw, wallClock)
        stopConnection()
        show(ConnectionPhase.CONNECTING)
        val expected = generation
        connection = scope.launch {
            try {
                approve(qr)
                currentCoroutineContext().ensureActive()
                if (expected == generation) connectRegistered()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (expected == generation) show(ConnectionPhase.ACTION_REQUIRED, failureCode(error)) }
        }
    }

    private suspend fun approve(qr: PairingQr) {
        val remaining = (qr.expiresAt.toEpochMilli() - wallClock()).coerceAtMost(120_000)
        if (remaining <= 0) throw ConnectionException("pairing_expired")
        try {
            withTimeout(remaining) {
                transportFactory(qr.trust).use { transport ->
                    val endpoint = EndpointResolver(transport).resolve(qr.serverId, qr.addresses)
                    val socket = transport.open(endpoint, qr.serverId, null, pairing = true)
                    try {
                        if (!socket.send(PairRequest(qr.pairingId, qr.secret, "ENE Android"))) throw ConnectionException("connection_closed")
                        while (true) {
                            when (val frame = ProtocolCodec.decode(socket.receive())) {
                                is PairPending -> {
                                    if (frame.pairing_id != qr.pairingId) throw ConnectionException("invalid_pairing_response")
                                    show(ConnectionPhase.AWAITING_APPROVAL)
                                }
                                is PairApproved -> {
                                    if (frame.pairing_id != qr.pairingId || frame.server_id != qr.serverId) throw ConnectionException("invalid_pairing_response")
                                    val credentials = DeviceCredentials.create(frame.server_id, frame.device_id, frame.registration_generation, frame.token, qr.trust.caCertificate, wallClock)
                                    clearCharacterCache()
                                    // 주소와 토큰 보관이 끝날 때까지 다음 전환은 취소된 작업을 실제로 회수한다.
                                    withContext(ioDispatcher) {
                                        profiles.save(ConnectionProfile(qr.serverId, listOf(endpoint) + qr.addresses.filter { it != endpoint }))
                                        registrations.save(credentials)
                                    }
                                    return@withTimeout
                                }
                                is PairFailed -> {
                                    if (frame.pairing_id != qr.pairingId) throw ConnectionException("invalid_pairing_response")
                                    throw ConnectionException(frame.code)
                                }
                                else -> throw ConnectionException("invalid_pairing_response")
                            }
                        }
                    } finally { socket.cancel() }
                }
            }
        } catch (_: TimeoutCancellationException) { throw ConnectionException("pairing_expired") }
    }

    private fun startRegistered() {
        connection = scope.launch { connectRegistered() }
    }

    private suspend fun connectRegistered() {
        val expectedGeneration = generation
        val backoff = RetryBackoff(jitter)
        var retry = false
        while (currentCoroutineContext().isActive) {
            try {
                val credentials = withContext(ioDispatcher) { registrations.load() }
                if (credentials == null) { mutableState.value = ConnectionViewState(draft = drafts.draft); return }
                mutableState.value = mutableState.value.copy(registered = true)
                val profile = withContext(ioDispatcher) { profiles.load() }
                if (profile == null || profile.serverId != credentials.serverId) throw ConnectionException("invalid_connection_settings")
                show(if (retry) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING)
                val trust = credentials.trustedServer(wallClock)
                transportFactory(trust).use { transport ->
                    val failures = mutableListOf<ConnectionException>()
                    suspend fun attempt(candidates: List<Endpoint>, network: LanScope? = null) {
                      for (endpoint in candidates.distinct().take(8)) {
                        trust.validate()
                        var synchronized = false
                        try {
                            if (network != null && !discovery.isCurrent(network)) throw ConnectionException("pc_unreachable")
                            EndpointResolver(transport).verify(credentials.serverId, endpoint)
                            if (network != null && !discovery.isCurrent(network)) throw ConnectionException("pc_unreachable")
                            runCandidate(credentials, transport, endpoint) {
                                if (!synchronized) {
                                    synchronized = true
                                    rememberEndpoint(expectedGeneration, credentials, endpoint, network)
                                }
                                backoff.reset()
                            }
                            throw ConnectionException("connection_closed")
                        } catch (error: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            trust.validate()
                            if (synchronized) throw ConnectionException("pc_unreachable")
                            failures.add(ConnectionException("pc_unreachable"))
                        } catch (error: ConnectionException) {
                            trust.validate()
                            if (synchronized || error.code == "authorization_revoked" && error.peerAuthenticated) throw error
                            failures.add(error)
                        }
                      }
                    }
                    attempt(profile.addresses)
                    val discovered = discovery.discover(profile.addresses.toSet())
                    mutableState.value = mutableState.value.copy(discoveryNotice = discovered.notice)
                    if (discovered.network != null && discovery.isCurrent(discovered.network)) attempt(discovered.endpoints, discovered.network)
                    throw candidateFailure(failures)
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                show(ConnectionPhase.RECONNECTING, "pc_unreachable")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val code = failureCode(error)
                if (code == "authorization_revoked" && error is ConnectionException && error.peerAuthenticated) {
                    drafts = DraftOutbox()
                    mutableState.value = ConnectionViewState(errorCode = code)
                    try {
                        clearCharacterCache()
                        withContext(ioDispatcher) { registrations.clear(); profiles.clear() }
                    }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { show(ConnectionPhase.ACTION_REQUIRED, failureCode(failure)) }
                    return
                }
                if (code !in RETRYABLE_CONNECTION_CODES) { show(ConnectionPhase.ACTION_REQUIRED, code); return }
                show(ConnectionPhase.RECONNECTING, code)
            }
            retry = true
            delay(backoff.nextMillis())
        }
    }

    private suspend fun rememberEndpoint(expected: Long, credentials: DeviceCredentials, endpoint: Endpoint, network: LanScope?) {
        // 취소 가능한 잠금만 사용한다. 등록 해제는 이 저장이 끝난 뒤 파일을 지운다.
        transition.withLock {
            if (!foreground || generation != expected || network != null && !discovery.isCurrent(network)) return
            try {
                withContext(ioDispatcher) {
                    if (registrations.load() != credentials) return@withContext
                    val current = profiles.load() ?: return@withContext
                    if (current.serverId != credentials.serverId || network != null && !discovery.isCurrent(network)) return@withContext
                    currentCoroutineContext().ensureActive()
                    val updated = current.copy(addresses = (listOf(endpoint) + current.addresses).distinct().take(8))
                    if (updated != current) profiles.save(updated)
                }
                mutableState.value = mutableState.value.copy(addressSaveNotice = null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(addressSaveNotice = "connection_settings_save_failed") }
        }
    }

    private suspend fun runCandidate(
        credentials: DeviceCredentials,
        transport: ConnectionTransport,
        endpoint: Endpoint,
        onSynchronized: suspend () -> Unit,
    ) {
        val socket = transport.open(endpoint, credentials.serverId, credentials.token, pairing = false)
        try {
            val requested = buildList {
                if (audioPlatform != null && transport.supportsAudio) add("audio_pcm_v1")
                if (characterPlatform?.supported == true && transport.supportsCharacter) {
                    add("character_v1"); add("character_controls_v1")
                }
            }
            val ready = withTimeout(5000) {
                if (!socket.send(Hello(requested))) throw ConnectionException("connection_closed")
                ProtocolCodec.decode(socket.receive()) as? Ready ?: throw ConnectionException("invalid_server_info")
            }
            if (ready.server_id != credentials.serverId || ready.registration_generation != credentials.generation) throw ConnectionException("registration_changed")
            if (ready.capabilities.any { it !in requested }) throw ConnectionException("invalid_server_info")
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.SYNCING, endpoint = endpoint, errorCode = null,
                audioOutput = AudioOutputStatus(reason = if ("audio_pcm_v1" in ready.capabilities) "syncing" else "audio_not_negotiated"))
            val session = ConversationSession(ready, nowMillis)
            val record = Active(credentials, session, socket)
            active = record
            val character = characterPlatform?.takeIf { "character_v1" in ready.capabilities }?.let { platform ->
                CharacterSession(ready, CharacterRepository.identity(credentials), CoroutineScope(currentCoroutineContext()), platform,
                    mediaFactory = { context, current -> transport.character(credentials.token, context) { record.mediaCurrent.get() && current() } },
                    send = socket::send, nowMillis = nowMillis,
                    isPublicAssistant = { id -> mutableState.value.messages.any { it.id == id && it.role == "assistant" } },
                    onState = { value -> if (active === record) {
                        if (record.characterLocalGeneration != value.viewGeneration) {
                            record.characterLocalGeneration = value.viewGeneration
                            characterViewGeneration++
                        }
                        mutableCharacterState.value = value.copy(viewGeneration = characterViewGeneration)
                    } },
                ).also {
                    record.character = it
                    it.placement(characterPlacement.value); it.panelVisible(characterPanelVisible)
                    it.resumed(resumedActivity); characterRenderer?.let(it::attach)
                }
            }
            if (character == null) mutableCharacterState.value = CharacterViewState("unsupported", viewGeneration = ++characterViewGeneration)
            val extensions = audioPlatform?.takeIf { "audio_pcm_v1" in ready.capabilities }?.let { platform ->
                ExtensionSession(ready, CoroutineScope(currentCoroutineContext()),
                    mediaFactory = { context -> transport.audio(credentials.token, context, record.mediaCurrent::get) },
                    platform = platform, send = socket::send, nowMillis = nowMillis,
                    isPublicAssistant = { id -> mutableState.value.messages.any { it.id == id && it.role == "assistant" } },
                    onOutput = { output -> if (active === record) mutableState.value = mutableState.value.copy(audioOutput = output) },
                    onFailure = { socket.cancel() },
                    extraCapabilities = ready.capabilities.toSet() - "audio_pcm_v1",
                    onPlayback = { character?.localPlayback(it) },
                ).also { record.extensions = it; it.resumed(resumedActivity) }
            }
            var pendingSyncHeaders = 0
            var headerEpoch = ready.server_epoch
            var headerConversation = ready.conversation_id
            if (!socket.send(drafts.syncRequest(credentials, session))) throw ConnectionException("connection_closed")
            publishDraft()
            exchangeSession(socket, nowMillis, {
                session.checkTimeout()
                val current = requireNotNull(active)
                if (!session.syncing && drafts.awaitingResult) {
                    val deadline = current.responseDeadline ?: (nowMillis() + 10_000).also { current.responseDeadline = it }
                    if (nowMillis() >= deadline) throw ConnectionException("request_status_timeout")
                } else current.responseDeadline = null
            }, onExtension = { extensions?.receive(it); character?.receive(it) }, onBaseHeader = { header ->
                when (header) {
                    is ResyncRequired -> { headerEpoch = header.server_epoch; headerConversation = header.conversation_id; pendingSyncHeaders++ }
                    is SnapshotBegin -> { headerEpoch = header.server_epoch; headerConversation = header.conversation_id; pendingSyncHeaders++ }
                    else -> Unit
                }
                if (pendingSyncHeaders > 0) {
                    extensions?.baseState(headerEpoch, headerConversation, false)
                    character?.baseState(headerEpoch, headerConversation, false)
                }
            }, onClosed = {
                record.mediaCurrent.set(false)
                extensions?.shutdown()
                character?.shutdown()
            }) { frame ->
                if (frame is ErrorMessage) throw ConnectionException(frame.code)
                val needsSync = withContext(decodeDispatcher) { session.consume(frame) }
                if (frame is ResyncRequired || frame is SnapshotBegin) pendingSyncHeaders--
                if (needsSync && !socket.send(drafts.syncRequest(credentials, session))) throw ConnectionException("connection_closed")
                if (frame is RequestStatus) drafts.status(frame)
                if (session.syncing) show(ConnectionPhase.SYNCING)
                else session.snapshot?.let { snapshot ->
                    mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTED,
                        messages = snapshot.messages, processing = snapshot.processing, errorCode = null)
                    drafts.synchronized(snapshot)?.let {
                        active?.responseDeadline = nowMillis() + 10_000
                        if (!socket.send(it)) throw ConnectionException("connection_closed")
                    }
                    onSynchronized()
                }
                if (pendingSyncHeaders > 0) {
                    extensions?.baseState(headerEpoch, headerConversation, false)
                    character?.baseState(headerEpoch, headerConversation, false)
                } else {
                    extensions?.baseState(session.serverEpoch, session.conversationId, !session.syncing)
                    character?.baseState(session.serverEpoch, session.conversationId, !session.syncing)
                }
                publishDraft()
            }
        } catch (error: ConnectionException) {
            throw authenticatedSessionFailure(error.code)
        } catch (error: ProtocolException) {
            throw authenticatedSessionFailure(error.code)
        } finally {
            val closing = active
            closing?.mediaCurrent?.set(false)
            closing?.extensions?.shutdown()
            closing?.character?.shutdown()
            closing?.extensions?.closeAndJoin()
            closing?.character?.closeAndJoin()
            if (active === closing) active = null
            socket.cancel()
        }
    }

    private suspend fun clearCharacterCache() {
        try { characterPlatform?.clearCache() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw ConnectionException("character_cache_cleanup_failed") }
    }

    private suspend fun stopConnection() {
        generation++
        active?.mediaCurrent?.set(false)
        active?.extensions?.shutdown()
        active?.character?.shutdown()
        connection?.cancelAndJoin()
        connection = null
    }

    private fun show(phase: ConnectionPhase, error: String? = null) {
        mutableState.value = mutableState.value.copy(phase = phase, errorCode = error)
    }

    override fun close() {
        active?.mediaCurrent?.set(false)
        active?.extensions?.shutdown()
        active?.character?.shutdown()
        scope.cancel()
    }

    companion object {
        internal fun failureCode(error: Exception): String = when (error) {
            is ConnectionException -> error.code
            is ProtocolException -> error.code
            else -> "connection_failed"
        }
    }
}
