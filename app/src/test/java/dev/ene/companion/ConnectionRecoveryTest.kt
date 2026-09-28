package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.ConnectionProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRecoveryTest {
    private val a = Endpoint.parse("192.0.2.41", 8765)
    private val b = Endpoint.parse("192.0.2.42", 8765)

    private inner class Scenario(scope: TestScope, discovery: PcDiscovery = NoPcDiscovery) {
        val credentials = ConnectionRepositoryTest.credentials()
        val registrations = ConnectionRepositoryTest.Registrations(credentials)
        val profiles = ConnectionRepositoryTest.Profiles().apply { value = ConnectionProfile(credentials.serverId, listOf(a, b)) }
        val probed = mutableListOf<Endpoint>()
        val opened = mutableListOf<Endpoint>()
        val sockets = mutableListOf<ConnectionRepositoryTest.Socket>()
        var beforeInfo: (Endpoint) -> ServerInfo = { ServerInfo(credentials.serverId, listOf(1)) }
        var beforeOpen: (Endpoint) -> Unit = {}
        var afterOpen: (Endpoint, ConnectionRepositoryTest.Socket) -> Unit = { _, socket -> synchronize(socket) }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val repository = ConnectionRepository(registrations, profiles, transportFactory = {
            object : ConnectionTransport {
                val owned = mutableListOf<ConnectionRepositoryTest.Socket>()
                override suspend fun info(endpoint: Endpoint): ServerInfo { probed.add(endpoint); return beforeInfo(endpoint) }
                override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
                    opened.add(endpoint)
                    assertEquals(credentials.token, token)
                    beforeOpen(endpoint)
                    return ConnectionRepositoryTest.Socket().also { owned.add(it); sockets.add(it); afterOpen(endpoint, it) }
                }
                override fun close() { owned.forEach { it.cancel() } }
            }
        }, dispatcher = dispatcher, ioDispatcher = dispatcher, decodeDispatcher = dispatcher,
            nowMillis = { scope.testScheduler.currentTime }, jitter = { 0.0 }, discovery = discovery)

        fun ready(socket: ConnectionRepositoryTest.Socket) {
            socket.offer(Ready(credentials.serverId, ConnectionRepositoryTest.epoch, ConnectionRepositoryTest.conversation, 1))
        }
        fun synchronize(socket: ConnectionRepositoryTest.Socket) {
            ready(socket)
            SessionFixtures.frames(emptyList()).forEach(socket::offer)
        }
    }

    @Test fun infoSuccessThenSocketFailureAdvancesToNextCandidate() = runTest {
        val scenario = Scenario(this)
        scenario.beforeOpen = { if (it == a) throw ConnectionException("connection_closed") }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(listOf(a, b), scenario.opened)
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            assertEquals(scenario.credentials, scenario.registrations.value)
            assertEquals(0, scenario.registrations.clears)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun readyTimeoutClosesFirstSocketThenConnectsSecond() = runTest {
        val scenario = Scenario(this)
        scenario.afterOpen = { endpoint, socket -> if (endpoint == b) scenario.synchronize(socket) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertFalse(scenario.repository.state.value.canSend)
            advanceTimeBy(5000); runCurrent()
            assertEquals(listOf(a, b), scenario.opened)
            assertTrue(scenario.sockets.first().cancelled)
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun firstSnapshotTimeoutAdvancesWithoutPublishingPartialConversation() = runTest {
        val scenario = Scenario(this)
        scenario.afterOpen = { endpoint, socket -> if (endpoint == a) scenario.ready(socket) else scenario.synchronize(socket) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(ConnectionPhase.SYNCING, scenario.repository.state.value.phase)
            assertTrue(scenario.repository.state.value.messages.isEmpty())
            advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf(a, b), scenario.opened)
            assertTrue(scenario.sockets.first().cancelled)
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun authenticatedProtocolFailureIsNotHiddenByLaterNetworkFailure() = runTest {
        val scenario = Scenario(this)
        scenario.beforeInfo = { if (it == b) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(2)) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(ConnectionPhase.ACTION_REQUIRED, scenario.repository.state.value.phase)
            assertEquals("unsupported_version", scenario.repository.state.value.errorCode)
            assertTrue(scenario.opened.isEmpty())
            assertEquals(0, scenario.registrations.clears)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun wrongIdentityNeverReceivesTokenAndDoesNotEraseRegistration() = runTest {
        val scenario = Scenario(this)
        scenario.beforeInfo = { if (it == a) throw ConnectionException("tls_identity_invalid") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(listOf(b), scenario.opened)
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            assertEquals(0, scenario.registrations.clears)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun untrustedRevocationIsIgnoredButAuthenticatedRevocationStopsCandidates() = runTest {
        for (authenticated in listOf(false, true)) {
            val scenario = Scenario(this)
            scenario.beforeOpen = { if (it == a) throw ConnectionException("authorization_revoked", authenticated) }
            try {
                scenario.repository.foreground(true); runCurrent()
                assertEquals(if (authenticated) listOf(a) else listOf(a, b), scenario.opened)
                assertEquals(if (authenticated) 1 else 0, scenario.registrations.clears)
                assertEquals(if (authenticated) ConnectionPhase.UNREGISTERED else ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            } finally { scenario.repository.close(); runCurrent() }
        }
    }

    @Test fun backgroundDuringReadyWaitClosesSocketWithoutNextCandidate() = runTest {
        val scenario = Scenario(this)
        scenario.afterOpen = { _, _ -> }
        try {
            scenario.repository.foreground(true); runCurrent()
            scenario.repository.foreground(false); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(listOf(a), scenario.opened)
            assertTrue(scenario.sockets.single().cancelled)
            assertEquals(ConnectionPhase.PAUSED, scenario.repository.state.value.phase)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun authenticatedSessionProtocolFailureSurvivesLaterUnreachableCandidate() = runTest {
        val scenario = Scenario(this)
        scenario.beforeInfo = { if (it == b) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        scenario.afterOpen = { _, socket -> socket.offer(Hello()) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals("invalid_server_info", scenario.repository.state.value.errorCode)
            assertEquals(ConnectionPhase.ACTION_REQUIRED, scenario.repository.state.value.phase)
            assertEquals(0, scenario.registrations.clears)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun sameAddressRecoversAfterTenMinutesOfflineWithoutPairingAgain() = runTest {
        val scenario = Scenario(this)
        scenario.beforeInfo = { if (testScheduler.currentTime < 600_000) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        try {
            scenario.repository.foreground(true); runCurrent()
            advanceTimeBy(600_000); runCurrent()
            assertEquals(0, scenario.registrations.clears)
            repeat(30) { if (scenario.repository.state.value.phase != ConnectionPhase.CONNECTED) { advanceTimeBy(1000); runCurrent() } }
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            assertEquals(0, scenario.registrations.saves)
        } finally { scenario.repository.close(); runCurrent() }
    }

    private inner class Discovery : PcDiscovery {
        val c = Endpoint.parse("198.51.100.41", 8765)
        val network = LanScope(1, listOf(Ipv4Prefix(0xc633640aL, 24)))
        var current = true
        var calls = 0
        override suspend fun discover(excluded: Set<Endpoint>): DiscoveryResult { calls++; return DiscoveryResult(listOf(c), network) }
        override fun isCurrent(network: LanScope) = current && network == this.network
    }

    @Test fun discoveredEndpointIsSavedOnlyAfterCompleteSynchronization() = runTest {
        val discovery = Discovery()
        val scenario = Scenario(this, discovery)
        scenario.beforeInfo = { if (it != discovery.c) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        scenario.afterOpen = { _, socket -> scenario.ready(socket) }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(listOf(discovery.c), scenario.opened)
            assertEquals(listOf(a, b), scenario.profiles.value!!.addresses)
            SessionFixtures.frames(emptyList()).forEach(scenario.sockets.single()::offer); runCurrent()
            assertEquals(listOf(discovery.c, a, b), scenario.profiles.value!!.addresses)
            assertEquals(scenario.credentials, scenario.registrations.value)
            assertEquals(0, scenario.registrations.saves)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun changedWifiCannotReceiveCredentialsOrSaveDiscoveredEndpoint() = runTest {
        val discovery = Discovery()
        val scenario = Scenario(this, discovery)
        scenario.beforeInfo = { endpoint ->
            if (endpoint != discovery.c) throw ConnectionException("pc_unreachable")
            discovery.current = false
            ServerInfo(scenario.credentials.serverId, listOf(1))
        }
        try {
            scenario.repository.foreground(true); runCurrent()
            assertTrue(scenario.opened.isEmpty())
            assertEquals(listOf(a, b), scenario.profiles.value!!.addresses)
            assertNotNull(scenario.registrations.value)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun healthySavedAddressNeverStartsDiscovery() = runTest {
        val discovery = Discovery()
        val scenario = Scenario(this, discovery)
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            assertEquals(0, discovery.calls)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun addressSaveFailureDoesNotDiscardHealthySessionOrExistingSettings() = runTest {
        val discovery = Discovery()
        val scenario = Scenario(this, discovery)
        scenario.beforeInfo = { if (it != discovery.c) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        scenario.profiles.failSave = true
        try {
            scenario.repository.foreground(true); runCurrent()
            assertEquals(ConnectionPhase.CONNECTED, scenario.repository.state.value.phase)
            assertEquals("connection_settings_save_failed", scenario.repository.state.value.addressSaveNotice)
            assertEquals(listOf(a, b), scenario.profiles.value!!.addresses)
            assertFalse(scenario.sockets.single().cancelled)
            assertNotNull(scenario.registrations.value)
        } finally { scenario.repository.close(); runCurrent() }
    }

    @Test fun registrationCommandsWaitForSynchronousAddressSave() = runBlocking {
      for (command in listOf("forget", "address", "pair", "background")) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val profiles = ConnectionRepositoryTest.Profiles().apply {
            beforeSave = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val registrations = ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials())
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val transports = java.util.concurrent.CopyOnWriteArrayList<ConnectionRepositoryTest.Transport>()
        val repository = ConnectionRepository(registrations, profiles, {
            ConnectionRepositoryTest.Transport().apply { firstCandidateFails = true }.also(transports::add)
        }, dispatcher)
        try {
            repository.foreground(true).join()
            withTimeout(3000) { repository.state.first { it.phase == ConnectionPhase.SYNCING } }
            SessionFixtures.frames(emptyList()).forEach(transports.first().socket::offer)
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            val changing = when (command) {
                "forget" -> repository.forget()
                "address" -> repository.updateAddress("192.0.2.99", 8765)
                "pair" -> repository.pair(ConnectionRepositoryTest().qr())
                else -> repository.foreground(false)
            }
            delay(50)
            assertFalse(changing.isCompleted)
            release.countDown()
            withTimeout(3000) { changing.join() }
            when (command) {
                "forget" -> { assertNull(profiles.value); assertNull(registrations.value); assertEquals(ConnectionPhase.UNREGISTERED, repository.state.value.phase) }
                "address" -> assertEquals(listOf(Endpoint.parse("192.0.2.99", 8765)), profiles.value!!.addresses)
                "background" -> { assertNotNull(registrations.value); assertEquals(ConnectionPhase.PAUSED, repository.state.value.phase) }
                "pair" -> { assertNotNull(registrations.value); assertEquals("192.0.2.2", profiles.value!!.addresses.first().host) }
            }
        } finally { release.countDown(); repository.close(); dispatcher.close() }
      }
    }

    @Test fun registrationReplacementBeforeSnapshotCannotSaveOldAddress() = runTest {
        val discovery = Discovery()
        val scenario = Scenario(this, discovery)
        scenario.beforeInfo = { if (it != discovery.c) throw ConnectionException("pc_unreachable") else ServerInfo(scenario.credentials.serverId, listOf(1)) }
        scenario.afterOpen = { _, socket -> scenario.ready(socket) }
        try {
            scenario.repository.foreground(true); runCurrent()
            scenario.registrations.value = ConnectionRepositoryTest.credentials()
            SessionFixtures.frames(emptyList()).forEach(scenario.sockets.single()::offer); runCurrent()
            assertEquals(listOf(a, b), scenario.profiles.value!!.addresses)
            assertEquals(0, scenario.profiles.saves)
        } finally { scenario.repository.close(); runCurrent() }
    }
}
