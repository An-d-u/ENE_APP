package dev.ene.companion.connection

import dev.ene.companion.pairing.PairingQr
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

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
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val transition = Mutex()
    private val mutableState = MutableStateFlow(ConnectionViewState())
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var generation = 0L
    private var connection: Job? = null
    private var drafts = DraftOutbox()
    private class Active(val credentials: DeviceCredentials, val session: ConversationSession, val socket: CompanionSocket) {
        var responseDeadline: Long? = null
    }
    private var active: Active? = null

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
                transportFactory(credentials.trustedServer(wallClock)).use { transport ->
                    val endpoint = EndpointResolver(transport).resolve(credentials.serverId, profile.addresses)
                    val socket = transport.open(endpoint, credentials.serverId, credentials.token, pairing = false)
                    try {
                        val ready = withTimeout(5000) {
                            if (!socket.send(Hello())) throw ConnectionException("connection_closed")
                            ProtocolCodec.decode(socket.receive()) as? Ready ?: throw ConnectionException("invalid_server_info")
                        }
                        if (ready.server_id != credentials.serverId || ready.registration_generation != credentials.generation) throw ConnectionException("registration_changed")
                        mutableState.value = mutableState.value.copy(phase = ConnectionPhase.SYNCING, endpoint = endpoint, errorCode = null)
                        val session = ConversationSession(ready, nowMillis)
                        active = Active(credentials, session, socket)
                        if (!socket.send(drafts.syncRequest(credentials, session))) throw ConnectionException("connection_closed")
                        publishDraft()
                        exchangeSession(socket, nowMillis, {
                            session.checkTimeout()
                            val current = requireNotNull(active)
                            if (!session.syncing && drafts.awaitingResult) {
                                val deadline = current.responseDeadline ?: (nowMillis() + 10_000).also { current.responseDeadline = it }
                                if (nowMillis() >= deadline) throw ConnectionException("request_status_timeout")
                            } else current.responseDeadline = null
                        }) { frame ->
                            if (frame is ErrorMessage) throw ConnectionException(frame.code)
                            val needsSync = withContext(decodeDispatcher) { session.consume(frame) }
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
                                backoff.reset()
                            }
                            publishDraft()
                        }
                    } finally { active = null; socket.cancel() }
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                show(ConnectionPhase.RECONNECTING, "pc_unreachable")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val code = failureCode(error)
                if (code == "authorization_revoked") {
                    drafts = DraftOutbox()
                    mutableState.value = ConnectionViewState(errorCode = code)
                    try { withContext(ioDispatcher) { registrations.clear() } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { show(ConnectionPhase.ACTION_REQUIRED, failureCode(failure)) }
                    return
                }
                if (code !in RETRYABLE) { show(ConnectionPhase.ACTION_REQUIRED, code); return }
                show(ConnectionPhase.RECONNECTING, code)
            }
            retry = true
            delay(backoff.nextMillis())
        }
    }

    private suspend fun stopConnection() {
        generation++
        connection?.cancelAndJoin()
        connection = null
    }

    private fun show(phase: ConnectionPhase, error: String? = null) {
        mutableState.value = mutableState.value.copy(phase = phase, errorCode = error)
    }

    override fun close() { scope.cancel() }

    companion object {
        private val RETRYABLE = setOf("pc_unreachable", "connection_closed", "heartbeat_timeout", "slow_consumer", "snapshot_timeout", "invalid_snapshot", "request_status_timeout", "sync_required")
        internal fun failureCode(error: Exception): String = when (error) {
            is ConnectionException -> error.code
            is ProtocolException -> error.code
            else -> "connection_failed"
        }
    }
}
