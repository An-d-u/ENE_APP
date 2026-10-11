package dev.ene.companion.connection

import dev.ene.companion.pairing.PairingQr
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.*
import dev.ene.companion.audio.AudioPlatform
import dev.ene.companion.character.*
import dev.ene.companion.input.*
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
    private val photoStorage: PhotoStorage? = null,
    private val recordingPlatform: RecordingPlatform? = null,
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
    private val characterPresentation = CharacterPresentationOwner {
        // 폐기된 View의 늦은 Compose 해제를 기다리는 동안 새 연결에 다시 붙이지 않는다.
        if (mutableCharacterState.value.viewGeneration != it.viewGeneration) characterRenderer = null
        mutableCharacterState.value = it
    }
    private var foreground = false
    private var resumedActivity = false
    private var generation = 0L
    private var connection: Job? = null
    private var drafts = DraftOutbox()
    private val photoDraft = photoStorage?.let { PhotoDraft(it, scope) }
    val photos = photoDraft?.state ?: MutableStateFlow(PhotoDraftState()).asStateFlow()
    private val photoOutbox = PhotoOutbox()
    private var photoTransfer: Job? = null
    private val chatActions = ChatActionState(nowMillis)
    private val chatDisplay = ChatDisplayState(nowMillis)
    private val chatProgress = ChatProgressState(nowMillis)
    private var visibleThoughtIds = emptyList<String>()
    private class Active(val credentials: DeviceCredentials, val session: ConversationSession, val socket: CompanionSocket) {
        var responseDeadline: Long? = null
        var pendingBaseHeaders = 0
        val mediaCurrent = AtomicBoolean(true)
        var extensions: ExtensionSession? = null
        var character: CharacterSession? = null
        var thoughts: ThoughtState? = null
        var input: InputSession? = null
        var inputReady: ExtensionsReady? = null
        var photoLimitsQuery: String? = null
        var nextPhotoLimits = 0L
        var images: ChatImages? = null
        var inputPhase: String? = null
        var stt: SttSession? = null
    }
    private var active: Active? = null
    private var sttDraft: SttDraft? = null
    private var reviewInput: InputSession? = null
    private fun draftIdentity() = mutableState.value.conversationScope?.let { "${it.registrationId}/${it.serverEpoch}/${it.conversationId}" }.orEmpty()
    fun recordingPermissionKey(): Any? = active?.input
    fun startRecording(expectedConnection: Any? = null): Job = command {
        if (expectedConnection != null && expectedConnection !== active?.input) return@command
        if (!resumedActivity || !canAct() || mutableState.value.processing.phase != "idle" || mutableState.value.stt.busy) return@command
        sttDraft = SttDraft(draftIdentity(), drafts.revision)
        mutableState.value = mutableState.value.copy(sttReview = null)
        active?.stt?.start()
    }
    fun finishRecording() { active?.stt?.finish() }
    fun cancelRecording() { active?.stt?.cancel() }
    fun microphoneDenied() { mutableState.value = mutableState.value.copy(stt = SttView(reason = "microphone_permission")) }
    fun appendTranscription(): Job = command {
        if (reviewInput == null || reviewInput !== active?.input || mutableState.value.phase != ConnectionPhase.CONNECTED) {
            discardTranscription(); return@command
        }
        mutableState.value.sttReview?.let { text ->
            val appended = SttDraft.appendText(drafts.draft, text)
            if (appended != null) { drafts.edit(appended); mutableState.value = mutableState.value.copy(sttReview = null); publishDraft() }
            else mutableState.value = mutableState.value.copy(stt = SttView(reason = "text_too_large"))
        }
    }
    fun discardTranscription() { reviewInput = null; mutableState.value = mutableState.value.copy(sttReview = null) }

    fun previousExit(value: PreviousExit) {
        diagnostics { copy(previousExit = value) }
    }

    private fun diagnostics(change: ConnectionDiagnostics.() -> ConnectionDiagnostics) {
        mutableState.value = mutableState.value.copy(diagnostics = mutableState.value.diagnostics.change())
    }
    private fun stage(value: ConnectionStage) { diagnostics { copy(stage = value) } }
    private fun failed(code: String) { diagnostics { failed(stage, code) } }

    init {
        scope.launch { characterPlacement.collect { active?.character?.placement(it) } }
        scope.launch { photos.collect { publishDraft() } }
    }
    fun selectPhotos(sources: List<String>) {
        if (photoOutbox.pending == null) photoDraft?.select(sources)
        active?.nextPhotoLimits = 0L
    }
    fun removePhoto(id: String) { if (photoOutbox.pending == null) photoDraft?.remove(id) }
    fun retryPhotos(): Job = command {
        val record = active ?: return@command
        if (photoOutbox.canRetry && mutableState.value.phase == ConnectionPhase.CONNECTED && record.session.snapshot?.processing?.phase == "idle") {
            startPhotoTransfer(record, photoOutbox.retry())
        }
    }
    private fun startPhotoTransfer(record: Active, pending: PhotoOutbox.Pending) {
        val input = record.input ?: run { photoOutbox.failedBeforeSend(); return }
        photoTransfer = scope.launch {
            try {
                val limits = input.photoLimits()
                if (!limits.allowed) throw ConnectionException(limits.reason)
                val refs = pending.photos.map { photo ->
                    val data = requireNotNull(photoStorage).read(photo)
                    val slot = input.upload("image", data)
                    InputImageRef(slot.upload_id, photo.sha256, photo.byteLength, photo.mime)
                }
                if (active !== record || record.input !== input || !record.mediaCurrent.get() || record.session.syncing ||
                    record.pendingBaseHeaders > 0 || record.session.conversationId != input.context.conversationId) throw ConnectionException("conversation_changed")
                val c = input.context
                val message = ChatImagesSend(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId,
                    pending.id, pending.text, refs)
                // send 반환값만으로 접수를 판단하지 않는다. 다음 연결에서 같은 ID를 조회한다.
                photoOutbox.sent()
                record.responseDeadline = nowMillis() + 10_000
                if (!record.socket.send(message)) record.socket.cancel()
                input.consumed(refs.map { it.upload_id })
            } catch (_: TimeoutCancellationException) {
                photoOutbox.failedBeforeSend()
                mutableState.value = mutableState.value.copy(inputNotice = "photo_send_failed")
            } catch (cancel: CancellationException) { photoOutbox.failedBeforeSend(); throw cancel }
            catch (error: Exception) {
                photoOutbox.failedBeforeSend()
                mutableState.value = mutableState.value.copy(inputNotice = (error as? ConnectionException)?.code ?: "photo_send_failed")
            } finally { input.cancelUploads(); publishDraft() }
        }
        publishDraft()
    }
    private fun inputSynchronized(record: Active, transport: ConnectionTransport) {
        val ready = record.inputReady ?: return
        if (!record.mediaCurrent.get() || record.session.syncing || record.pendingBaseHeaders > 0) return
        val c = ExtensionContext(ready.registration_generation, record.session.serverEpoch, ready.connection_generation, record.session.conversationId)
        if (record.input?.context == c) {
            if (record.inputPhase != record.session.snapshot?.processing?.phase) {
                record.inputPhase = record.session.snapshot?.processing?.phase
                if ("chat_images_v1" in ready.capabilities) queryPhotoLimits(record, c)
            }
            return
        }
        record.images?.close()
        record.stt?.close()
        record.input?.close()
        record.input = InputSession(c, transport.input(record.credentials.token, c) {
            record.mediaCurrent.get() && record.session.serverEpoch == c.serverEpoch && record.session.conversationId == c.conversationId
        }, record.socket::send)
        if ("stt_input_v1" in ready.capabilities && recordingPlatform != null) {
            record.stt = SttSession(requireNotNull(record.input), recordingPlatform, scope, record.socket::send,
                publish = { if (active === record) mutableState.value = mutableState.value.copy(stt = it) },
                result = { text ->
                    if (active === record && record.mediaCurrent.get()) {
                        val appended = sttDraft?.append(draftIdentity(), drafts.revision, drafts.draft, text)
                        if (appended == null) {
                            reviewInput = record.input
                            mutableState.value = mutableState.value.copy(sttReview = text)
                        }
                        else { drafts.edit(appended); publishDraft() }
                    }
                })
        }
        if ("chat_images_v1" in ready.capabilities) {
            record.images = ChatImages(requireNotNull(record.input), scope, record.socket::send, nowMillis) {
                if (active === record) mutableState.value = mutableState.value.copy(chatImages = it)
            }
            queryPhotoLimits(record, c)
        }
    }
    private fun queryPhotoLimits(record: Active, c: ExtensionContext) {
        // 응답이 없으면 자동 반복하지 않는다. 응답 성공 또는 사용자의 재선택으로만 갱신한다.
        record.nextPhotoLimits = Long.MAX_VALUE
        val query = java.util.UUID.randomUUID().toString().also { record.photoLimitsQuery = it }
        record.socket.send(ChatImagesLimitsRequest(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, query))
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
        if (!value) active?.stt?.cancel()
        if (value && !resumedActivity) command { chatDisplay.refresh(); publishChatDisplay() }
        resumedActivity = value
        active?.extensions?.resumed(value, changingConfigurations)
        characterPresentation.resumed(value)
        active?.character?.resumed(value, changingConfigurations)
    }

    fun attachCharacter(renderer: CharacterRenderer) {
        characterRenderer = renderer
        characterPresentation.attach(renderer)
        active?.character?.attach(renderer)
    }
    fun detachCharacter(renderer: CharacterRenderer) {
        if (characterRenderer !== renderer) return
        active?.character?.detach(renderer)
        characterPresentation.detach(renderer)
        characterRenderer = null
    }
    fun characterEvent(renderer: CharacterRenderer, event: CharacterEvent) {
        if (characterRenderer === renderer) active?.character?.event(renderer, event)
    }
    fun characterFailed(renderer: CharacterRenderer, code: String) {
        if (characterRenderer === renderer) {
            active?.character?.rendererFailed(renderer, code)
            characterPresentation.rendererFailed(renderer, code)
        }
    }
    fun retryCharacter() { active?.character?.retry() }
    fun openCharacterSettings() { active?.character?.openSettings() }
    fun closeCharacterSettings() { active?.character?.closeSettings() }
    fun previewCharacterSettings(key: String, value: JsonElement, parameter: Boolean) { active?.character?.previewSettings(key, value, parameter) }
    fun submitCharacterSettings() { active?.character?.submitSettings() }

    fun visibleThoughts(ids: List<String>): Job = command {
        visibleThoughtIds = ids.distinct().take(32)
        active?.thoughts?.visible(visibleThoughtIds)
        publishThoughts()
    }

    fun retryThought(id: String): Job = command { active?.thoughts?.retry(id); publishThoughts() }

    private fun publishThoughts() {
        mutableState.value = mutableState.value.copy(thoughts = active?.thoughts?.entries ?: emptyMap())
    }

    private fun pumpThoughts(record: Active) {
        val thoughts = record.thoughts ?: return
        if (!record.mediaCurrent.get() || record.session.syncing || record.pendingBaseHeaders > 0) return
        record.session.snapshot?.let(thoughts::synchronize)
        thoughts.nextRequest()?.let { if (!record.socket.send(it)) throw ConnectionException("connection_closed") }
        publishThoughts()
    }

    private fun publishChatProgress() {
        mutableState.value = mutableState.value.copy(chatProgress = chatProgress.view)
    }

    private fun pumpChatProgress(record: Active) {
        if (active !== record || !record.mediaCurrent.get()) return
        chatProgress.nextRequest()?.let { if (!record.socket.send(it)) throw ConnectionException("connection_closed") }
        publishChatProgress()
    }

    private fun publishChatDisplay() {
        mutableState.value = mutableState.value.copy(chatDisplay = chatDisplay.view)
    }

    private fun pumpChatDisplay(record: Active) {
        if (!record.mediaCurrent.get()) return
        chatDisplay.nextRequest()?.let { if (!record.socket.send(it)) throw ConnectionException("connection_closed") }
        publishChatDisplay()
    }

    fun editDraft(text: String): Job = command { drafts.edit(text); publishDraft() }
    fun sendDraft(): Job = command {
        val current = active ?: return@command
        if (!mutableState.value.canSend || current.session.syncing || current.pendingBaseHeaders > 0 || !current.mediaCurrent.get()) return@command
        if (photos.value.images.isNotEmpty()) {
            if (drafts.draft.toByteArray(Charsets.UTF_8).size > 16384) throw ConnectionException("text_too_large")
            val input = current.input ?: return@command
            val pending = photoOutbox.create(CharacterRepository.identity(current.credentials), input.context, drafts.draft, drafts.revision, photos.value.images)
            startPhotoTransfer(current, pending)
            return@command
        }
        val message = drafts.create(current.credentials, current.session)
        current.responseDeadline = nowMillis() + 10_000
        publishDraft()
        if (!current.socket.send(message)) {
            show(ConnectionPhase.RECONNECTING, "connection_closed")
            current.socket.cancel()
        }
    }

    private fun canAct(): Boolean = active?.let {
        mutableState.value.phase == ConnectionPhase.CONNECTED && !it.session.syncing &&
            it.mediaCurrent.get() && it.pendingBaseHeaders == 0 && drafts.state == null && photoOutbox.pending == null && !mutableState.value.stt.busy && !chatActions.view.busy
    } == true

    fun openMessageEditor(targetId: String): Job = command {
        if (canAct()) chatActions.openEditor(targetId)
        publishDraft()
    }
    fun editMessageDraft(text: String): Job = command { chatActions.editText(text); publishDraft() }
    fun cancelMessageEditor(): Job = command { chatActions.cancelEditor(); publishDraft() }
    fun reopenMessageEditor(): Job = command { chatActions.reopenEditor(); publishDraft() }
    fun submitMessageEdit(): Job = command {
        if (canAct()) chatActions.createEdit()?.let(::sendAction)
    }
    fun rerollMessage(targetId: String): Job = command {
        if (canAct()) chatActions.createReroll(targetId)?.let(::sendAction)
    }
    private fun sendAction(message: ChatAction) {
        val current = active ?: return
        current.responseDeadline = nowMillis() + 10_000
        publishDraft()
        if (!current.socket.send(message)) {
            show(ConnectionPhase.RECONNECTING, "connection_closed")
            current.socket.cancel()
        }
    }

    private fun syncRequest(record: Active): SyncRequest = if (chatActions.hasPending) chatActions.syncRequest()
        else if (photoOutbox.pending != null) photoOutbox.sync(CharacterRepository.identity(record.credentials),
            ExtensionContext(record.credentials.generation, record.session.serverEpoch, "", record.session.conversationId))
        else drafts.syncRequest(record.credentials, record.session)

    private fun pumpChatActions(record: Active) {
        // consume 작업이 끝난 경계에서만 조립기를 변경한다.
        if (!chatActions.refreshing) record.session.finishRefresh()
        try { chatActions.checkTimeout(record.session.syncing) }
        catch (_: IllegalStateException) { throw ConnectionException("request_status_timeout") }
        chatActions.nextQuery()?.let {
            if (it.refresh) {
                record.session.beginRefresh()
                chatActions.baseState(record.session.serverEpoch, record.session.conversationId, false)
            }
            if (!record.socket.send(it)) throw ConnectionException("connection_closed")
        }
        publishDraft()
    }

    private fun publishDraft() {
        val actions = chatActions.view.let { value ->
            if (drafts.state == null && photoOutbox.pending == null && mutableState.value.phase == ConnectionPhase.CONNECTED) value
            else value.copy(canEdit = false, canReroll = false,
                editReason = "busy", rerollReason = "busy", editor = value.editor?.copy(canSubmit = false))
        }
        mutableState.value = mutableState.value.copy(draft = drafts.draft, sendState = drafts.state ?: photoOutbox.pending?.let { if (it.accepted) "accepted" else "photo_sending" },
            photoCount = photos.value.images.size, photoPreparing = photos.value.preparing, photoRetry = photoOutbox.canRetry,
            chatActions = actions, errorCode = drafts.notice ?: mutableState.value.errorCode)
    }

    fun updateAddress(host: String, port: Int): Job = command {
        val endpoint = Endpoint.parse(host, port)
        val credentials = withContext(ioDispatcher) { registrations.load() } ?: return@command
        stopConnection(CharacterStopReason.SAME_IDENTITY_RETRY)
        show(ConnectionPhase.ACTION_REQUIRED)
        withContext(ioDispatcher) { profiles.save(ConnectionProfile(credentials.serverId, listOf(endpoint))) }
        if (foreground) startRegistered()
    }

    fun forget(): Job = command {
        stopConnection(CharacterStopReason.REGISTRATION_CHANGE)
        drafts = DraftOutbox()
        photoOutbox.clear(); photoDraft?.reset()
        chatActions.forget()
        chatDisplay.forget()
        chatProgress.forget()
        val retainedDiagnostics = ConnectionDiagnostics(previousExit = mutableState.value.diagnostics.previousExit)
        mutableState.value = ConnectionViewState(phase = ConnectionPhase.ACTION_REQUIRED, diagnostics = retainedDiagnostics)
        clearCharacterCache()
        withContext(ioDispatcher) { registrations.clear(); profiles.clear() }
        mutableState.value = ConnectionViewState(diagnostics = retainedDiagnostics)
    }

    fun retry(): Job = command {
        if (foreground) { stopConnection(CharacterStopReason.SAME_IDENTITY_RETRY); startRegistered() }
    }

    fun cancelPairing(): Job = command {
        stopConnection(CharacterStopReason.REGISTRATION_CHANGE)
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
            stopConnection(CharacterStopReason.BACKGROUND)
            if (active) startRegistered() else show(ConnectionPhase.PAUSED)
        }
    }

    fun pair(raw: String): Job = command {
        if (!foreground) return@command
        val qr = PairingQr.parse(raw, wallClock)
        stopConnection(CharacterStopReason.REGISTRATION_CHANGE)
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
                                    chatActions.forget()
                                    chatDisplay.forget()
                                    chatProgress.forget()
                                    photoOutbox.clear(); photoDraft?.reset()
                                    publishChatDisplay()
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
            var failureRecorded = false
            try {
                stage(ConnectionStage.SAVED_ADDRESSES)
                val credentials = withContext(ioDispatcher) { registrations.load() }
                if (credentials == null) {
                    characterPresentation.discard(CharacterStopReason.REGISTRATION_CHANGE)
                    mutableState.value = ConnectionViewState(draft = drafts.draft, diagnostics = mutableState.value.diagnostics.copy(stage = ConnectionStage.IDLE))
                    return
                }
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
                            stage(ConnectionStage.SERVER_INFO)
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
                            failed("pc_unreachable"); failureRecorded = true
                            if (synchronized) throw ConnectionException("pc_unreachable")
                            failures.add(ConnectionException("pc_unreachable"))
                        } catch (error: ConnectionException) {
                            // 후보 실패를 연결 불가로 합치기 전에 신뢰 실패의 보관 모델을 폐기한다.
                            if (error.code in TLS_FAILURE_CODES || error.code in setOf("server_mismatch", "unexpected_server", "registration_changed")) {
                                characterPresentation.discard(CharacterStopReason.TRUST_FAILURE)
                            }
                            trust.validate()
                            failed(error.code); failureRecorded = true
                            if (synchronized || error.code == "authorization_revoked" && error.peerAuthenticated) throw error
                            failures.add(error)
                        }
                      }
                    }
                    attempt(profile.addresses)
                    stage(ConnectionStage.DISCOVERY)
                    val discovered = discovery.discover(profile.addresses.toSet())
                    diagnostics { copy(discoveryNotice = discovered.notice?.let(::diagnosticCode)) }
                    if (discovered.network != null && discovery.isCurrent(discovered.network)) attempt(discovered.endpoints, discovered.network)
                    throw candidateFailure(failures)
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failed("pc_unreachable")
                show(ConnectionPhase.RECONNECTING, "pc_unreachable")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val code = failureCode(error)
                if (!failureRecorded) failed(code)
                if (code == "authorization_revoked" && error is ConnectionException && error.peerAuthenticated) {
                    drafts = DraftOutbox()
                    photoOutbox.clear(); photoDraft?.reset()
                    chatActions.forget()
                    chatDisplay.forget()
                    chatProgress.forget()
                    mutableState.value = ConnectionViewState(errorCode = code, diagnostics = mutableState.value.diagnostics.copy(stage = ConnectionStage.IDLE))
                    try {
                        clearCharacterCache()
                        withContext(ioDispatcher) { registrations.clear(); profiles.clear() }
                    }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { show(ConnectionPhase.ACTION_REQUIRED, failureCode(failure)) }
                    return
                }
                if (code !in RETRYABLE_CONNECTION_CODES) {
                    characterPresentation.discard(CharacterStopReason.TRUST_FAILURE)
                    show(ConnectionPhase.ACTION_REQUIRED, code); return
                }
                show(ConnectionPhase.RECONNECTING, code)
            }
            retry = true
            stage(ConnectionStage.RETRY_WAIT)
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
                diagnostics { copy(storageNotice = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { diagnostics { copy(storageNotice = "connection_settings_save_failed") } }
        }
    }

    private suspend fun runCandidate(
        credentials: DeviceCredentials,
        transport: ConnectionTransport,
        endpoint: Endpoint,
        onSynchronized: suspend () -> Unit,
    ) {
        stage(ConnectionStage.SECURE_SESSION)
        val socket = transport.open(endpoint, credentials.serverId, credentials.token, pairing = false)
        var owned: Active? = null
        try {
            val requested = buildList {
                add("chat_actions_v1")
                add("message_thoughts_v1")
                add("chat_display_v1")
                add("chat_progress_v1")
                if (photoStorage != null && transport.supportsInput) add("chat_images_v1")
                if (recordingPlatform != null && transport.supportsInput) add("stt_input_v1")
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
            stage(ConnectionStage.INITIAL_SYNC)
            mutableState.value = mutableState.value.copy(phase = ConnectionPhase.SYNCING, endpoint = endpoint, errorCode = null,
                audioOutput = AudioOutputStatus(reason = if ("audio_pcm_v1" in ready.capabilities) "syncing" else "audio_not_negotiated"))
            val session = ConversationSession(ready, nowMillis)
            val record = Active(credentials, session, socket).also { owned = it }
            record.thoughts = ThoughtState(ready, nowMillis).also { it.visible(visibleThoughtIds) }
            active = record
            mutableState.value = mutableState.value.copy(photoSupported = "chat_images_v1" in ready.capabilities,
                photoAllowed = false, inputNotice = null, sttSupported = "stt_input_v1" in ready.capabilities)
            publishThoughts()
            chatActions.connected(CharacterRepository.identity(credentials), ready)
            chatDisplay.connected(CharacterRepository.identity(credentials), ready)
            chatProgress.connected(CharacterRepository.identity(credentials), ready)
            publishChatProgress()
            publishChatDisplay()
            val character = characterPlatform?.takeIf { "character_v1" in ready.capabilities }?.let { platform ->
                CharacterSession(ready, CharacterRepository.identity(credentials), CoroutineScope(currentCoroutineContext()), platform,
                    mediaFactory = { context, current -> transport.character(credentials.token, context) { record.mediaCurrent.get() && current() } },
                    send = socket::send, nowMillis = nowMillis,
                    isPublicAssistant = { id -> mutableState.value.messages.any { it.id == id && it.role == "assistant" } },
                    presentation = characterPresentation,
                ).also {
                    record.character = it
                    it.placement(characterPlacement.value); it.panelVisible(characterPanelVisible)
                    it.resumed(resumedActivity); characterRenderer?.let(it::attach)
                }
            }
            if (character == null) characterPresentation.discard(CharacterStopReason.UNSUPPORTED)
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
            if (!socket.send(syncRequest(record))) throw ConnectionException("connection_closed")
            publishDraft()
            exchangeSession(socket, nowMillis, {
                session.checkTimeout()
                pumpChatActions(record)
                pumpThoughts(record)
                pumpChatDisplay(record)
                pumpChatProgress(record)
                if (!session.syncing && record.pendingBaseHeaders == 0) session.snapshot?.let { record.images?.synchronize(it, visibleThoughtIds) }
                if (!session.syncing && record.pendingBaseHeaders == 0 && photos.value.images.isNotEmpty() &&
                    mutableState.value.photoSupported && nowMillis() >= record.nextPhotoLimits) record.input?.let { queryPhotoLimits(record, it.context) }
                val current = requireNotNull(active)
                if (!session.syncing && (drafts.awaitingResult || chatActions.awaitingResult ||
                        photoOutbox.pending?.let { it.sent && !it.accepted && !it.unknown } == true)) {
                    val deadline = current.responseDeadline ?: (nowMillis() + 10_000).also { current.responseDeadline = it }
                    if (nowMillis() >= deadline) throw ConnectionException("request_status_timeout")
                } else current.responseDeadline = null
            }, onExtension = {
                if (it is ExtensionsReady && it.capabilities.any { cap -> cap in setOf("chat_images_v1", "stt_input_v1") }) {
                    record.inputReady = it
                    inputSynchronized(record, transport)
                }
                record.input?.receive(it)
                record.images?.receive(it)
                record.stt?.receive(it)
                if (it is AudioOffer) record.stt?.cancel("recording_interrupted")
                if (it is ChatImagesLimits && it.query_id == record.photoLimitsQuery && record.input?.context?.let { c ->
                        it.registration_generation == c.registrationGeneration && it.server_epoch == c.serverEpoch &&
                        it.connection_generation == c.connectionGeneration && it.conversation_id == c.conversationId } == true) {
                    record.nextPhotoLimits = nowMillis() + 5000
                    mutableState.value = mutableState.value.copy(photoAllowed = it.allowed,
                        inputNotice = if (it.allowed) null else it.reason)
                }
                if (active === record && record.mediaCurrent.get()) {
                    chatProgress.receive(it)
                    publishChatProgress()
                }
                chatDisplay.receive(it)
                publishChatDisplay()
                record.thoughts?.receive(it)
                publishThoughts()
                when (it) {
                    is ExtensionsReady -> chatActions.ready(it)
                    is ChatActionsState -> chatActions.receiveState(it)
                    else -> Unit
                }
                publishDraft()
                extensions?.receive(it); character?.receive(it)
            }, onBaseHeader = { header ->
                if (header is ResyncRequired || header is SnapshotBegin || (header is ChatEvent && header.op == "append")) {
                    record.thoughts?.pause()
                    publishThoughts()
                }
                if (header is ResyncRequired || header is SnapshotBegin || header is ChatEvent) {
                    record.pendingBaseHeaders++
                    val (epoch, conversation) = when (header) {
                        is ResyncRequired -> header.server_epoch to header.conversation_id
                        is SnapshotBegin -> header.server_epoch to header.conversation_id
                        is ChatEvent -> header.server_epoch to header.conversation_id
                        else -> error("도달할 수 없는 헤더")
                    }
                    chatActions.baseState(epoch, conversation, false)
                    if (record.input?.context?.let { it.serverEpoch != epoch || it.conversationId != conversation } == true) {
                        discardTranscription()
                        record.stt?.close(); record.input?.close(); record.images?.close()
                        record.input = null; record.stt = null; record.images = null
                    }
                    chatProgress.baseState(epoch, conversation, false)
                    publishChatProgress()
                    publishDraft()
                }
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
                discardTranscription()
                record.mediaCurrent.set(false)
                photoTransfer?.cancel(); record.input?.close()
                record.images?.close()
                record.stt?.close()
                record.thoughts?.pause()
                publishThoughts()
                extensions?.shutdown()
                character?.shutdown(CharacterStopReason.TRANSIENT_DISCONNECT)
                chatActions.disconnected()
                chatDisplay.disconnected()
                chatProgress.disconnected()
                publishChatProgress()
                publishChatDisplay()
                publishDraft()
            }) { frame ->
                if (frame is ErrorMessage) throw ConnectionException(frame.code)
                val needsSync = withContext(decodeDispatcher) { session.consume(frame) }
                if (frame is ResyncRequired || frame is SnapshotBegin) pendingSyncHeaders--
                if (frame is ResyncRequired || frame is SnapshotBegin || frame is ChatEvent) record.pendingBaseHeaders--
                if (record.pendingBaseHeaders > 0) chatActions.baseState(headerEpoch, headerConversation, false)
                else chatActions.baseState(session.serverEpoch, session.conversationId, !session.syncing)
                if (needsSync && !socket.send(syncRequest(record))) throw ConnectionException("connection_closed")
                if (frame is RequestStatus) {
                    if (photoOutbox.pending?.id == frame.request_id) {
                        photoOutbox.status(frame)?.let { accepted ->
                            drafts.acceptSnapshot(accepted.version)
                            photoDraft?.accepted(accepted.photos.map { photo -> photo.id }.toSet())
                        }
                        if (frame.state in setOf("failed", "rejected")) mutableState.value = mutableState.value.copy(inputNotice = frame.code ?: "photo_send_failed")
                        publishDraft()
                    } else if (chatActions.hasPending) chatActions.receiveStatus(frame) else drafts.status(frame)
                }
                if (active === record && record.mediaCurrent.get() && record.pendingBaseHeaders == 0) {
                    chatProgress.baseState(session.serverEpoch, session.conversationId, !session.syncing, session.snapshot?.processing)
                    publishChatProgress()
                }
                if (session.syncing && record.mediaCurrent.get()) { show(ConnectionPhase.SYNCING); stage(ConnectionStage.INITIAL_SYNC) }
                else if (!session.syncing && active === record && record.mediaCurrent.get() && record.pendingBaseHeaders == 0) session.snapshot?.let { snapshot ->
                    stage(ConnectionStage.CONNECTED)
                    mutableState.value = mutableState.value.copy(phase = ConnectionPhase.CONNECTED,
                        messages = snapshot.messages, processing = snapshot.processing, errorCode = null,
                        conversationScope = ConversationScope(CharacterRepository.identity(credentials), snapshot.serverEpoch, snapshot.conversationId),
                        conversationRevision = snapshot.conversationRevision)
                    chatActions.snapshot(snapshot)
                    if (snapshot.processing.phase != "idle") record.stt?.cancel("recording_interrupted")
                    if (record.pendingBaseHeaders > 0) chatActions.baseState(headerEpoch, headerConversation, false)
                    drafts.synchronized(snapshot)?.let {
                        active?.responseDeadline = nowMillis() + 10_000
                        if (!socket.send(it)) throw ConnectionException("connection_closed")
                    }
                    onSynchronized()
                    inputSynchronized(record, transport)
                }
                if (pendingSyncHeaders > 0) {
                    extensions?.baseState(headerEpoch, headerConversation, false)
                    character?.baseState(headerEpoch, headerConversation, false)
                } else {
                    extensions?.baseState(session.serverEpoch, session.conversationId, !session.syncing)
                    character?.baseState(session.serverEpoch, session.conversationId, !session.syncing)
                }
                pumpChatActions(record)
                pumpThoughts(record)
                pumpChatProgress(record)
            }
        } catch (error: ConnectionException) {
            throw authenticatedSessionFailure(error.code)
        } catch (error: ProtocolException) {
            throw authenticatedSessionFailure(error.code)
        } finally {
            val closing = owned
            discardTranscription()
            closing?.mediaCurrent?.set(false)
            photoTransfer?.cancelAndJoin()
            closing?.input?.close()
            closing?.images?.close()
            closing?.stt?.close()
            closing?.extensions?.shutdown()
            closing?.character?.shutdown(CharacterStopReason.TRANSIENT_DISCONNECT)
            closing?.extensions?.closeAndJoin()
            closing?.character?.closeAndJoin()
            if (active === closing) active = null
            publishThoughts()
            chatActions.disconnected()
            chatDisplay.disconnected()
            chatProgress.disconnected()
            publishChatProgress()
            publishChatDisplay()
            publishDraft()
            socket.cancel()
        }
    }

    private suspend fun clearCharacterCache() {
        characterPresentation.discard(CharacterStopReason.REGISTRATION_CHANGE)
        try { characterPlatform?.clearCache() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw ConnectionException("character_cache_cleanup_failed") }
    }

    private suspend fun stopConnection(reason: CharacterStopReason) {
        active?.stt?.close()
        characterPresentation.pause()
        generation++
        active?.mediaCurrent?.set(false)
        active?.extensions?.shutdown()
        active?.character?.shutdown(reason)
        if (!reason.mayRetain) characterPresentation.discard(reason)
        connection?.cancelAndJoin()
        connection = null
        stage(ConnectionStage.IDLE)
    }

    private fun show(phase: ConnectionPhase, error: String? = null) {
        mutableState.value = mutableState.value.copy(phase = phase, errorCode = error)
        if (phase == ConnectionPhase.ACTION_REQUIRED || phase == ConnectionPhase.PAUSED) stage(ConnectionStage.IDLE)
    }

    override fun close() {
        active?.stt?.close()
        active?.input?.close()
        active?.mediaCurrent?.set(false)
        active?.extensions?.shutdown()
        active?.character?.shutdown(CharacterStopReason.CLOSED)
        characterPresentation.discard(CharacterStopReason.CLOSED)
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
