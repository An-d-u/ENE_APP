package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.*
import dev.ene.companion.character.CharacterPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.security.SecureRandom
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryTest {
    companion object {
        val ca by lazy { TlsTestCertificates.ca() }
        const val serverId = TlsTestCertificates.SERVER_ID
        const val epoch = "00000000-0000-4000-8000-000000000011"
        const val conversation = "00000000-0000-4000-8000-000000000012"
        const val pairingId = "00000000-0000-4000-8000-000000000013"
        fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun credentials() = DeviceCredentials.create(serverId, "00000000-0000-4000-8000-000000000002", 1, secret(), TlsTestCertificates.encoded(ca.certificate))
    }

    class Registrations(var value: DeviceCredentials?) : RegistrationStorage {
        var saves = 0
        var clears = 0
        var failSave = false
        var failClear = false
        var beforeSave: (() -> Unit)? = null
        override fun load() = value
        override fun save(credentials: DeviceCredentials) { beforeSave?.invoke(); if (failSave) throw ConnectionException("registration_save_failed"); value = credentials; saves++ }
        override fun clear() { if (failClear) throw ConnectionException("registration_clear_failed"); value = null; clears++ }
    }
    class Profiles : ConnectionSettingsStorage {
        var value: ConnectionProfile? = ConnectionProfile(serverId, listOf(Endpoint.parse("192.0.2.1", 8765), Endpoint.parse("192.0.2.2", 8765)))
        override fun load() = value
        override fun save(profile: ConnectionProfile) { value = profile }
        override fun clear() { value = null }
    }
    class Socket : CompanionSocket {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<WireMessage>()
        var cancelled = false
        override suspend fun receive(): String = incoming.receiveCatching().getOrNull() ?: throw ConnectionException("connection_closed")
        override fun send(message: WireMessage): Boolean { if (cancelled) return false; sent.add(message); return true }
        override fun cancel() { cancelled = true; incoming.cancel() }
        fun offer(message: WireMessage) { check(incoming.trySend(ProtocolCodec.encode(message)).isSuccess) }
    }
    class Transport : ConnectionTransport, Closeable {
        val socket = Socket()
        val probed = mutableListOf<Endpoint>()
        val opened = mutableListOf<Pair<Endpoint, String?>>()
        var firstCandidateFails = false
        var failure: String? = null
        var readyEpoch = epoch
        var closed = false
        override suspend fun info(endpoint: Endpoint): ServerInfo {
            probed.add(endpoint)
            if (firstCandidateFails && endpoint.host == "192.0.2.1") throw ConnectionException("pc_unreachable")
            return ServerInfo(serverId, listOf(1))
        }
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
            opened.add(endpoint to token)
            failure?.let { throw ConnectionException(it) }
            if (!pairing) socket.offer(Ready(serverId, readyEpoch, conversation, 1))
            return socket
        }
        override fun close() { closed = true; socket.cancel() }
    }

    private fun TestScope.repository(registrations: Registrations, profiles: Profiles = Profiles(), character: CharacterPlatform? = null, factory: () -> Transport): ConnectionRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ConnectionRepository(registrations, profiles, transportFactory = { factory() }, dispatcher = dispatcher,
            ioDispatcher = dispatcher, decodeDispatcher = dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 }, characterPlatform = character)
    }

    @Test fun forgetClearsCacheBeforeCredentialsAndPreservesRegistrationOnCleanupFailure() = runTest {
        val registrations = Registrations(credentials())
        val profiles = Profiles()
        var cleared = 0
        var fail = true
        val platform = CharacterPlatform(false, clearCache = {
            assertNotNull(registrations.value); assertNotNull(profiles.value)
            if (fail) error("합성 캐시 삭제 실패")
            cleared++
        }) { _, _, _ -> error("모델 로드 없음") }
        val repo = repository(registrations, profiles, platform) { error("네트워크 없음") }
        try {
            repo.forget(); runCurrent()
            assertEquals("character_cache_cleanup_failed", repo.state.value.errorCode)
            assertNotNull(registrations.value); assertEquals(0, registrations.clears)
            fail = false; repo.forget(); runCurrent()
            assertEquals(1, cleared); assertNull(registrations.value); assertNull(profiles.value)
            assertEquals(ConnectionPhase.UNREGISTERED, repo.state.value.phase)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun approvedReplacementClearsCacheOnlyAfterApprovalAndBeforeNewCredentials() = runTest {
        val previous = credentials()
        val registrations = Registrations(previous)
        val transports = mutableListOf<Transport>()
        var clears = 0
        var fail = true
        val platform = CharacterPlatform(false, clearCache = {
            assertEquals(previous, registrations.value)
            assertTrue(transports.first().closed)
            if (fail) error("합성 캐시 삭제 실패")
            clears++
        }) { _, _, _ -> error("모델 로드 없음") }
        val repo = repository(registrations, character = platform) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent()
            repeat(2) { attempt ->
                repo.pair(qr()); runCurrent()
                val pairing = transports.last()
                assertEquals(0, clears)
                val approved = credentials()
                pairing.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, 1, approved.token)); runCurrent()
                if (attempt == 0) {
                    assertEquals("character_cache_cleanup_failed", repo.state.value.errorCode)
                    assertEquals(previous, registrations.value); assertEquals(0, registrations.saves)
                    fail = false
                } else {
                    assertEquals(1, clears); assertEquals(approved, registrations.value)
                }
            }
        } finally { repo.close(); runCurrent() }
    }

    @Test fun confirmedRevocationClearsCacheBeforeForgettingCredentials() = runTest {
        val registrations = Registrations(credentials())
        var cleared = false
        val platform = CharacterPlatform(false, clearCache = { assertNotNull(registrations.value); cleared = true }) {
            _, _, _ -> error("모델 로드 없음")
        }
        val repo = repository(registrations, character = platform) { Transport().apply { failure = "authorization_revoked" } }
        try {
            repo.foreground(true); runCurrent()
            assertTrue(cleared); assertNull(registrations.value)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun noRegistrationDoesNotOpenNetwork() = runTest {
        val repo = repository(Registrations(null)) { error("등록 전에 네트워크 생성 금지") }
        try { repo.foreground(true); runCurrent(); assertEquals(ConnectionPhase.UNREGISTERED, repo.state.value.phase) }
        finally { repo.close(); runCurrent() }
    }

    @Test fun candidateFallbackOpensOneSocketAndWaitsForCompleteSync() = runTest {
        val transport = Transport().apply { firstCandidateFails = true }
        val registration = credentials()
        val repo = repository(Registrations(registration)) { transport }
        try {
            repo.foreground(true); runCurrent()
            assertEquals(2, transport.probed.size)
            assertEquals(listOf(Endpoint.parse("192.0.2.2", 8765) to registration.token), transport.opened)
            assertEquals(listOf(Hello(), SyncRequest()), transport.socket.sent)
            assertEquals(ConnectionPhase.SYNCING, repo.state.value.phase)
            assertFalse(repo.state.value.canSend)
        } finally { repo.close(); runCurrent(); assertTrue(transport.closed) }
    }

    @Test fun repeatedForegroundDoesNotReconnectButBackgroundReturnDoes() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); repo.foreground(true); runCurrent()
            assertEquals(1, transports.size)
            repo.foreground(false); runCurrent()
            assertTrue(transports[0].closed)
            assertEquals(ConnectionPhase.PAUSED, repo.state.value.phase)
            repo.foreground(true); runCurrent()
            assertEquals(2, transports.size)
            assertEquals(ConnectionPhase.SYNCING, repo.state.value.phase)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun onlyAuthenticatedRevocationClearsRegistration() = runTest {
        for (code in listOf("authorization_unconfirmed", "tls_identity_invalid", "pc_unreachable", "authorization_revoked")) {
            val registrations = Registrations(credentials())
            val repo = repository(registrations) { Transport().apply { failure = code } }
            try {
                repo.foreground(true); runCurrent()
                assertEquals(code, if (code == "authorization_revoked") 1 else 0, registrations.clears)
                assertFalse(repo.state.value.canSend)
            } finally { repo.close(); runCurrent() }
        }
    }

    private fun qr() = buildJsonObject {
        put("protocol_version", 1); put("transport", "tls_v1"); put("server_id", serverId)
        put("pairing_id", pairingId); put("secret", secret()); put("ca_certificate", TlsTestCertificates.encoded(ca.certificate))
        put("expires_at", java.time.Instant.now().plusSeconds(120).toString())
        putJsonArray("addresses") { add(buildJsonObject { put("host", "192.0.2.2"); put("port", 8765) }) }
    }.toString()

    @Test fun pairingPersistsNothingBeforeExplicitPcApproval() = runTest {
        val registrations = Registrations(null)
        val transports = mutableListOf<Transport>()
        val repo = repository(registrations) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); repo.pair(qr()); runCurrent()
            val pairing = transports.single()
            assertNull(pairing.opened.single().second)
            assertTrue(pairing.socket.sent.single() is PairRequest)
            pairing.socket.offer(PairPending(pairingId)); runCurrent()
            assertEquals(ConnectionPhase.AWAITING_APPROVAL, repo.state.value.phase)
            assertEquals(0, registrations.saves)
            val approved = credentials()
            pairing.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, approved.generation, approved.token)); runCurrent()
            assertEquals(approved, registrations.value)
            assertTrue(pairing.closed)
            assertEquals(2, transports.size)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun failedApprovalSaveNeverOpensAuthenticatedSocket() = runTest {
        val registrations = Registrations(null).apply { failSave = true }
        val transport = Transport()
        val repo = repository(registrations) { transport }
        try {
            repo.foreground(true); runCurrent(); repo.pair(qr()); runCurrent()
            val approved = credentials()
            transport.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, 1, approved.token)); runCurrent()
            assertEquals("registration_save_failed", repo.state.value.errorCode)
            assertEquals(ConnectionPhase.ACTION_REQUIRED, repo.state.value.phase)
            assertEquals(1, transport.opened.size)
            assertTrue(transport.closed)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun completeSyncIsAtomicAndContiguousEventsAreAppliedOnce() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent()
            val first = SessionFixtures.message()
            val frames = SessionFixtures.frames(listOf(first), seq = 4, revision = 2)
            frames.dropLast(1).forEach(transport.socket::offer); runCurrent()
            assertEquals(ConnectionPhase.SYNCING, repo.state.value.phase)
            assertTrue(repo.state.value.messages.isEmpty())
            transport.socket.offer(frames.last()); runCurrent()
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            assertEquals(listOf(first), repo.state.value.messages)
            val second = SessionFixtures.message("종이 달을 접었습니다.")
            val event = ChatEvent(epoch, conversation, 5, 3, "append", SessionFixtures.payload(second))
            transport.socket.offer(event); transport.socket.offer(event); runCurrent()
            assertEquals(listOf(first, second), repo.state.value.messages)
            transport.socket.offer(ChatEvent(epoch, conversation, 6, 3, "processing", buildJsonObject { put("phase", "responding") })); runCurrent()
            assertFalse(repo.state.value.canSend)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun gapRequestsOneResyncAndRetainsLastCompleteConversation() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent()
            val first = SessionFixtures.message()
            SessionFixtures.frames(listOf(first)).forEach(transport.socket::offer); runCurrent()
            val event = ChatEvent(epoch, conversation, 2, 1, "append", SessionFixtures.payload(SessionFixtures.message()))
            repeat(3) { transport.socket.offer(event) }; runCurrent()
            assertEquals(ConnectionPhase.SYNCING, repo.state.value.phase)
            assertEquals(listOf(first), repo.state.value.messages)
            assertEquals(2, transport.socket.sent.filterIsInstance<SyncRequest>().size)
            SessionFixtures.frames(emptyList(), seq = 2, revision = 1).forEach(transport.socket::offer); runCurrent()
            assertTrue(repo.state.value.messages.isEmpty())
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun missingSnapshotBeginTimesOutWithoutErasingRegistration() = runTest {
        val registrations = Registrations(credentials())
        val transport = Transport()
        val repo = repository(registrations) { transport }
        try {
            repo.foreground(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertEquals("snapshot_timeout", repo.state.value.errorCode)
            assertEquals(ConnectionPhase.RECONNECTING, repo.state.value.phase)
            assertTrue(transport.closed)
            assertNotNull(registrations.value)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun onlyMatchingPongKeepsConnectionAlive() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames().forEach(transport.socket::offer); runCurrent()
            advanceTimeBy(15_000); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<Ping>().size)
            transport.socket.offer(Pong(java.util.UUID.randomUUID().toString()))
            transport.socket.offer(Ping(pairingId)); runCurrent()
            assertEquals(Pong(pairingId), transport.socket.sent.last())
            advanceTimeBy(10_000); runCurrent()
            assertEquals("heartbeat_timeout", repo.state.value.errorCode)
            assertTrue(transport.closed)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun draftIsNotSentBeforeSyncAndReservedDoesNotClearIt() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent(); repo.editDraft("가상 연못을 그려 주세요."); repo.sendDraft(); runCurrent()
            assertTrue(transport.socket.sent.filterIsInstance<SendText>().isEmpty())
            SessionFixtures.frames().forEach(transport.socket::offer); runCurrent()
            repo.sendDraft(); repo.sendDraft(); runCurrent()
            val sent = transport.socket.sent.filterIsInstance<SendText>().single()
            transport.socket.offer(RequestStatus(epoch, conversation, sent.request_id, "reserved")); runCurrent()
            assertEquals(sent.text, repo.state.value.draft)
            assertFalse(repo.state.value.canSend)
            repo.editDraft("다음 가상 도형을 적습니다."); runCurrent()
            transport.socket.offer(RequestStatus(epoch, conversation, sent.request_id, "accepted")); runCurrent()
            assertEquals("다음 가상 도형을 적습니다.", repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun acceptedClearsOnlySubmittedDraftAndFailurePreservesUnacceptedDraft() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transport.socket::offer); runCurrent()
            repo.editDraft("가상 책장을 칠해 주세요."); repo.sendDraft(); runCurrent()
            val first = transport.socket.sent.filterIsInstance<SendText>().single()
            transport.socket.offer(RequestStatus(epoch, conversation, first.request_id, "failed", code = "preparation_failed")); runCurrent()
            assertEquals(first.text, repo.state.value.draft)
            repo.sendDraft(); runCurrent()
            val second = transport.socket.sent.filterIsInstance<SendText>().last()
            assertNotEquals(first.request_id, second.request_id)
            transport.socket.offer(RequestStatus(epoch, conversation, second.request_id, "accepted")); runCurrent()
            assertEquals("", repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun reconnectQueriesPendingBeforeRetryAndUnknownReusesExactRequest() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("파란 모형 배를 만듭니다."); repo.sendDraft(); runCurrent()
            val sent = transports[0].socket.sent.filterIsInstance<SendText>().single()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            val socket = transports[1].socket
            assertEquals(SyncRequest(listOf(sent.request_id)), socket.sent.last())
            assertTrue(socket.sent.filterIsInstance<SendText>().isEmpty())
            socket.offer(RequestStatus(epoch, conversation, sent.request_id, "unknown")); runCurrent()
            assertTrue(socket.sent.filterIsInstance<SendText>().isEmpty())
            SessionFixtures.frames().forEach(socket::offer); runCurrent()
            assertEquals(listOf(sent), socket.sent.filterIsInstance<SendText>())
            socket.offer(RequestStatus(epoch, conversation, sent.request_id, "unknown")); runCurrent()
            assertEquals(1, socket.sent.filterIsInstance<SendText>().size)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun snapshotAcknowledgesLostAcceptanceWithoutRetry() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("가상의 작은 탑입니다."); repo.sendDraft(); runCurrent()
            val sent = transports[0].socket.sent.filterIsInstance<SendText>().single()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            val socket = transports[1].socket
            SessionFixtures.frames(listOf(SessionFixtures.message(sent.text, sent.request_id))).forEach(socket::offer); runCurrent()
            socket.offer(RequestStatus(epoch, conversation, sent.request_id, "completed")); runCurrent()
            assertEquals("", repo.state.value.draft)
            assertTrue(socket.sent.filterIsInstance<SendText>().isEmpty())
            assertEquals(1, repo.state.value.messages.size)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun newPcEpochPreservesDraftButNeverRetriesOldRequest() = runTest {
        val transports = mutableListOf<Transport>()
        val newEpoch = java.util.UUID.randomUUID().toString()
        val repo = repository(Registrations(credentials())) { Transport().apply { if (transports.isNotEmpty()) readyEpoch = newEpoch }.also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("노란 가상 구체입니다."); repo.sendDraft(); runCurrent()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            val socket = transports[1].socket
            assertEquals(SyncRequest(), socket.sent.last())
            SessionFixtures.frames(epoch = newEpoch).forEach(socket::offer); runCurrent()
            assertTrue(socket.sent.filterIsInstance<SendText>().isEmpty())
            assertEquals("노란 가상 구체입니다.", repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun invalidDraftDoesNotCloseHealthyConnection() = runTest {
        val transport = Transport()
        val repo = repository(Registrations(credentials())) { transport }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transport.socket::offer); runCurrent()
            repo.editDraft("x".repeat(16_385)); repo.sendDraft(); runCurrent()
            assertEquals("text_too_large", repo.state.value.errorCode)
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            assertFalse(transport.closed)
            assertTrue(transport.socket.sent.filterIsInstance<SendText>().isEmpty())
        } finally { repo.close(); runCurrent() }
    }

    @Test fun missingAcceptanceTriggersRecoveryQuery() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("가상 원뿔을 놓았습니다."); repo.sendDraft(); runCurrent()
            val sent = transports[0].socket.sent.filterIsInstance<SendText>().single()
            advanceTimeBy(11_000); runCurrent()
            assertEquals(2, transports.size)
            assertEquals(SyncRequest(listOf(sent.request_id)), transports[1].socket.sent.last())
            assertEquals(sent.text, repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun addressRepairKeepsOriginalTrustAndForgetErasesMemory() = runTest {
        val registrations = Registrations(credentials())
        val original = registrations.value
        val profiles = Profiles()
        val transports = mutableListOf<Transport>()
        val repo = repository(registrations, profiles) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames(listOf(SessionFixtures.message())).forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("가상 종이 성입니다."); repo.updateAddress("192.0.2.9", 9876); runCurrent()
            assertEquals(original, registrations.value)
            assertEquals(Endpoint.parse("192.0.2.9", 9876), transports[1].opened.single().first)
            repo.forget(); runCurrent()
            assertNull(registrations.value); assertNull(profiles.value)
            assertEquals(ConnectionPhase.UNREGISTERED, repo.state.value.phase)
            assertEquals("", repo.state.value.draft); assertTrue(repo.state.value.messages.isEmpty())
            assertTrue(transports.all { it.closed })
        } finally { repo.close(); runCurrent() }
    }

    @Test fun revocationEraseFailureRequiresActionWithoutCrashing() = runTest {
        val registrations = Registrations(credentials()).apply { failClear = true }
        val repo = repository(registrations) { Transport().apply { failure = "authorization_revoked" } }
        try {
            repo.foreground(true); runCurrent()
            assertEquals(ConnectionPhase.ACTION_REQUIRED, repo.state.value.phase)
            assertEquals("registration_clear_failed", repo.state.value.errorCode)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun cancelledPairingCannotSaveLateApproval() = runTest {
        val registrations = Registrations(null)
        val transport = Transport()
        val repo = repository(registrations) { transport }
        try {
            repo.foreground(true); runCurrent(); repo.pair(qr()); runCurrent()
            val approved = credentials()
            repo.cancelPairing()
            transport.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, 1, approved.token))
            runCurrent()
            assertEquals(0, registrations.saves)
            assertEquals(ConnectionPhase.UNREGISTERED, repo.state.value.phase)
            assertTrue(transport.closed)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun pcResetRaceResynchronizesWithoutLosingDraft() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("가상 조각을 쌓습니다."); repo.sendDraft(); runCurrent()
            val sent = transports[0].socket.sent.filterIsInstance<SendText>().single()
            transports[0].socket.offer(ErrorMessage("sync_required", sent.request_id)); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertEquals(2, transports.size)
            assertEquals(SyncRequest(listOf(sent.request_id)), transports[1].socket.sent.last())
            SessionFixtures.frames().forEach(transports[1].socket::offer)
            transports[1].socket.offer(RequestStatus(epoch, conversation, sent.request_id, "unknown")); runCurrent()
            assertEquals(listOf(sent), transports[1].socket.sent.filterIsInstance<SendText>())
            assertEquals(sent.text, repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun answeredUnknownWaitsForIdleWithoutReconnectLoop() = runTest {
        val transports = mutableListOf<Transport>()
        val repo = repository(Registrations(credentials())) { Transport().also(transports::add) }
        try {
            repo.foreground(true); runCurrent(); SessionFixtures.frames().forEach(transports[0].socket::offer); runCurrent()
            repo.editDraft("가상 타일을 놓습니다."); repo.sendDraft(); runCurrent()
            val sent = transports[0].socket.sent.filterIsInstance<SendText>().single()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            val socket = transports[1].socket
            SessionFixtures.frames(processing = ProcessingState("responding")).forEach(socket::offer)
            socket.offer(RequestStatus(epoch, conversation, sent.request_id, "unknown")); runCurrent()
            advanceTimeBy(15_000); runCurrent()
            assertEquals(2, transports.size)
            assertFalse(socket.cancelled)
            socket.offer(Pong(socket.sent.filterIsInstance<Ping>().last().nonce)); runCurrent()
            socket.offer(ChatEvent(epoch, conversation, 1, 0, "processing", buildJsonObject { put("phase", "idle") })); runCurrent()
            assertEquals(listOf(sent), socket.sent.filterIsInstance<SendText>())
        } finally { repo.close(); runCurrent() }
    }

    @Test fun forgetWaitsForAlreadyRunningSynchronousSave() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val registrations = Registrations(null).apply { beforeSave = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val transport = Transport()
        val creations = java.util.concurrent.atomic.AtomicInteger()
        val repo = ConnectionRepository(registrations, Profiles(), transportFactory = { creations.incrementAndGet(); transport }, dispatcher = dispatcher)
        try {
            repo.foreground(true).join(); repo.pair(qr()).join()
            val approved = credentials()
            transport.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, 1, approved.token))
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            val forgetting = repo.forget()
            delay(50)
            assertFalse(forgetting.isCompleted)
            release.countDown()
            withTimeout(3000) { forgetting.join() }
            assertNull(registrations.value)
            assertEquals(1, creations.get())
            assertEquals(ConnectionPhase.UNREGISTERED, repo.state.value.phase)
        } finally { release.countDown(); repo.close(); dispatcher.close() }
    }

    @Test fun backgroundCancellationDiscardsDeferredOldDecode() = runTest {
        class HeldDispatcher : CoroutineDispatcher() {
            val tasks = ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
            fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        }
        val decode = HeldDispatcher()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transports = mutableListOf<Transport>()
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), transportFactory = { Transport().also(transports::add) },
            dispatcher = dispatcher, ioDispatcher = dispatcher, decodeDispatcher = decode, nowMillis = { testScheduler.currentTime })
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(listOf(SessionFixtures.message())).forEach(transports[0].socket::offer); runCurrent()
            assertTrue(decode.tasks.isNotEmpty())
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            assertEquals(1, transports.size)
            decode.drain(); runCurrent()
            assertEquals(2, transports.size)
            assertTrue(repo.state.value.messages.isEmpty())
        } finally { repo.close(); decode.drain(); runCurrent() }
    }
}
