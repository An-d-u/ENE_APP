package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 가상 네트워크·그래픽 장치와 실제 캐시로 연결 경계의 자원 수명을 검증한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class CharacterRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture(val scope: TestScope) {
        val registrations = ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials())
        val profiles = ConnectionRepositoryTest.Profiles().apply { value = value!!.copy(addresses = value!!.addresses.take(1)) }
        val cache = CharacterCache(temporary.newFolder())
        var bytes = "{}".toByteArray()
        var snapshot = CharacterSnapshot.parse(CharacterFixtures.manifest(bytes))
        var epoch = ConnectionRepositoryTest.epoch
        var failure: String? = null
        var supported = true
        var downloads = 0
        var loads = 0
        var mediaOpened = 0
        var mediaClosed = 0
        var cleanupFailure = false
        val sockets = mutableListOf<ConnectionRepositoryTest.Socket>()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val renderer = RetainedRenderer()
        val repository = ConnectionRepository(registrations, profiles, transportFactory = {
            object : ConnectionTransport {
                val socket = ConnectionRepositoryTest.Socket().also(sockets::add)
                override val supportsCharacter = true
                override suspend fun info(endpoint: Endpoint): ServerInfo {
                    failure?.let { throw ConnectionException(it, peerAuthenticated = it == "authorization_revoked") }
                    return ServerInfo(ConnectionRepositoryTest.serverId, listOf(1))
                }
                override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
                    if (!pairing) socket.offer(Ready(ConnectionRepositoryTest.serverId, epoch, ConnectionRepositoryTest.conversation,
                        1, if (supported) listOf("character_v1") else emptyList()))
                    return socket
                }
                override fun character(token: String, context: ExtensionContext, isCurrent: () -> Boolean): CharacterMedia {
                    mediaOpened++
                    return object : CharacterMedia {
                        var closed = false
                        override suspend fun manifest() = error("합성 로더가 캐시 경계를 직접 검사합니다.")
                        override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) = error("합성 자산")
                        override fun close() { if (!closed) { closed = true; mediaClosed++ } }
                    }
                }
                override fun close() = socket.cancel()
            }
        }, dispatcher = dispatcher, ioDispatcher = dispatcher, decodeDispatcher = dispatcher,
            nowMillis = { scope.testScheduler.currentTime }, jitter = { 0.0 },
            characterPlatform = CharacterPlatform(true, clearCache = {
                assertNull(renderer.mounted); assertNull(renderer.stream)
                assertEquals(mediaOpened, mediaClosed)
                if (cleanupFailure) error("합성 정리 실패")
                cache.clear()
            }) { identity, _, current ->
                check(current()); loads++
                CharacterLoad(snapshot, cache.acquire(identity, snapshot) ?: cache.begin(identity, snapshot).use { ticket ->
                    downloads++; ticket.open(snapshot.entryAssetId!!).use { it.write(bytes) }; ticket.commit()
                })
            })
        fun connect() {
            repository.activityResumed(true); repository.foreground(true); scope.runCurrent()
            synchronize()
        }
        fun synchronize() {
            val socket = sockets.last()
            SessionFixtures.frames(epoch = epoch).forEach(socket::offer)
            if (supported) socket.offer(ExtensionsReady(1, epoch, audioId(200 + sockets.size), listOf("character_v1")))
            scope.runCurrent(); scope.advanceTimeBy(300); scope.runCurrent()
        }
        fun ready(target: RetainedRenderer = renderer) {
            repository.characterEvent(target, CharacterEvent("ready", snapshot.modelVersion, presentationGeneration = target.generation))
        }
        fun prepare() {
            repository.attachCharacter(renderer); connect(); ready()
            assertTrue(renderer.visible)
            renderer.stream = renderer.mounted!!.open(snapshot.entryAssetId!!)
        }
        fun background() { repository.activityResumed(false); repository.foreground(false); scope.runCurrent() }
        fun close() { repository.close(); scope.runCurrent(); cache.clear() }
    }

    @Test fun threeBackgroundCyclesKeepViewAndAssetsButRequireFreshReadiness() = runTest {
        val f = Fixture(this)
        try {
            f.prepare(); val view = f.repository.characterState.value.viewGeneration
            repeat(3) {
                val old = f.renderer.generation
                f.background()
                assertTrue(f.repository.characterState.value.retainRenderer)
                assertFalse(f.renderer.visible); assertEquals(0, f.renderer.clears)
                assertEquals(f.mediaOpened, f.mediaClosed)
                val loads = f.loads; advanceTimeBy(2000); runCurrent(); assertEquals(loads, f.loads)
                f.connect()
                assertEquals("refreshing", f.repository.characterState.value.status)
                f.repository.characterEvent(f.renderer, CharacterEvent("ready", f.snapshot.modelVersion, presentationGeneration = old))
                assertFalse(f.renderer.visible)
                f.ready(); assertTrue(f.renderer.visible)
                assertEquals(view, f.repository.characterState.value.viewGeneration)
                assertEquals(1, f.downloads)
            }
        } finally { f.close() }
    }

    @Test fun briefPauseTransientSocketAndAddressRepairRetainSameModel() = runTest {
        val f = Fixture(this)
        try {
            f.prepare()
            f.repository.activityResumed(false); f.repository.activityResumed(true)
            advanceTimeBy(300); runCurrent(); assertFalse(f.renderer.visible); f.ready()
            f.sockets.last().cancel(); runCurrent()
            assertTrue(f.repository.characterState.value.retainRenderer); assertFalse(f.renderer.visible)
            advanceTimeBy(2000); runCurrent(); f.synchronize(); f.ready()
            f.repository.updateAddress("192.0.2.9", 8765); runCurrent(); f.synchronize(); f.ready()
            assertTrue(f.renderer.visible); assertEquals(0, f.renderer.clears); assertEquals(1, f.downloads)
        } finally { f.close() }
    }

    @Test fun serverRestartRetainsModelButModelReplacementReleasesOldAssets() = runTest {
        val f = Fixture(this)
        try {
            f.prepare(); f.background(); f.epoch = audioId(220); f.connect(); f.ready()
            assertEquals(0, f.renderer.clears); assertEquals(1, f.downloads)
            f.background(); f.bytes = "{\"synthetic\":1}".toByteArray()
            f.snapshot = CharacterSnapshot.parse(CharacterFixtures.manifest(f.bytes))
            f.connect(); assertFalse(f.renderer.visible); f.ready()
            assertTrue(f.renderer.clears > 0); assertEquals(2, f.downloads)
            assertNull(f.renderer.stream)
        } finally { f.close() }
    }

    @Test fun trustFailureDiscardsRetainedModelBeforeCandidateFailureAggregation() = runTest {
        val f = Fixture(this)
        try {
            f.prepare(); f.background(); f.failure = "tls_identity_invalid"
            f.repository.foreground(true); runCurrent()
            assertEquals("pc_unreachable", f.repository.state.value.errorCode)
            assertFalse(f.repository.characterState.value.retainRenderer); assertNull(f.renderer.mounted)
            assertNotNull(f.registrations.value)
        } finally { f.close() }
    }

    @Test fun unsupportedPeerAndMissingRegistrationDiscardRetainedModel() = runTest {
        for (missing in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.prepare(); f.background()
                if (missing) f.registrations.value = null else f.supported = false
                f.repository.foreground(true); runCurrent()
                assertFalse(f.repository.characterState.value.retainRenderer); assertNull(f.renderer.mounted)
            } finally { f.close() }
        }
    }

    @Test fun forgetReleasesPinsBeforeCacheClearAndKeepsRegistrationIfCleanupFails() = runTest {
        val f = Fixture(this)
        try {
            f.prepare(); f.background(); f.cleanupFailure = true
            f.repository.forget(); runCurrent()
            assertEquals("character_cache_cleanup_failed", f.repository.state.value.errorCode)
            assertNull(f.renderer.mounted); assertNotNull(f.registrations.value)
            f.cleanupFailure = false; f.repository.forget(); runCurrent()
            assertNull(f.registrations.value); assertEquals(0, f.cache.versionCount)
        } finally { f.close() }
    }

    @Test fun rendererDeathWithoutActiveConnectionCannotReappearOnComposeUpdate() = runTest {
        val f = Fixture(this)
        try {
            f.prepare(); f.background()
            f.repository.characterFailed(f.renderer, "character_renderer_gone")
            assertEquals("error", f.repository.characterState.value.status)
            assertFalse(f.repository.characterState.value.retainRenderer)
            f.repository.activityResumed(true); assertFalse(f.renderer.visible)
            assertNull(f.renderer.mounted)
            val oldSnapshots = f.renderer.snapshots.size
            f.connect()
            assertEquals(oldSnapshots, f.renderer.snapshots.size)
            val replacement = RetainedRenderer()
            f.repository.attachCharacter(replacement); f.ready(replacement)
            f.repository.detachCharacter(f.renderer)
            assertTrue(replacement.visible)
        } finally { f.close() }
    }

    @Test fun pairingAndAuthenticatedRevocationReleaseRetainedResources() = runTest {
        for (pairing in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.prepare()
                if (pairing) {
                    f.repository.pair("{}"); runCurrent()
                    assertTrue(f.renderer.visible)
                    f.repository.pair(ConnectionRepositoryTest().qr()); runCurrent()
                } else {
                    f.background(); f.failure = "authorization_revoked"
                    f.repository.foreground(true); runCurrent()
                    assertNull(f.registrations.value); assertEquals(0, f.cache.versionCount)
                }
                assertFalse(f.repository.characterState.value.retainRenderer)
                assertNull(f.renderer.mounted); assertNull(f.renderer.stream)
                assertEquals(f.mediaOpened, f.mediaClosed)
            } finally { f.close() }
        }
    }

    @Test fun rendererFailureAndLateReleaseCannotDetachExplicitReplacement() = runTest {
        val f = Fixture(this)
        try {
            f.prepare()
            f.repository.characterFailed(f.renderer, "character_renderer_gone")
            assertEquals(ConnectionPhase.CONNECTED, f.repository.state.value.phase)
            assertEquals("error", f.repository.characterState.value.status)
            assertNull(f.renderer.mounted)
            f.cache.clear()
            f.repository.retryCharacter(); advanceTimeBy(300); runCurrent()
            val replacement = RetainedRenderer()
            f.repository.attachCharacter(replacement)
            f.repository.detachCharacter(f.renderer)
            f.repository.characterFailed(f.renderer, "character_renderer_gone")
            f.ready(replacement)
            assertTrue(replacement.visible)
            assertEquals(ConnectionPhase.CONNECTED, f.repository.state.value.phase)
        } finally { f.close() }
    }

    @Test fun actualRepositoryReadsFreshManifestWithoutRetransferringRetainedAssets() = runBlocking {
        java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) {
                val cache = CharacterCache(temporary.newFolder())
                val loader = CharacterRepository(cache)
                val payload = "{}".toByteArray()
                val manifest = CharacterFixtures.manifest(payload)
                val snapshot = CharacterSnapshot.parse(manifest)
                val manifests = java.util.concurrent.atomic.AtomicInteger()
                val assets = java.util.concurrent.atomic.AtomicInteger()
                val sockets = mutableListOf<ConnectionRepositoryTest.Socket>()
                val renderer = RetainedRenderer()
                val repo = ConnectionRepository(ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials()),
                    ConnectionRepositoryTest.Profiles(), dispatcher = dispatcher, transportFactory = {
                        object : ConnectionTransport {
                            val socket = ConnectionRepositoryTest.Socket().also(sockets::add)
                            override val supportsCharacter = true
                            override suspend fun info(endpoint: Endpoint) = ServerInfo(ConnectionRepositoryTest.serverId, listOf(1))
                            override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
                                socket.offer(Ready(ConnectionRepositoryTest.serverId, ConnectionRepositoryTest.epoch,
                                    ConnectionRepositoryTest.conversation, 1, listOf("character_v1")))
                                return socket
                            }
                            override fun character(token: String, context: ExtensionContext, isCurrent: () -> Boolean) = object : CharacterMedia {
                                override suspend fun manifest(): ByteArray { manifests.incrementAndGet(); return manifest.toByteArray() }
                                override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
                                    assets.incrementAndGet(); onChunk(payload, payload.size)
                                }
                                override fun close() = Unit
                            }
                            override fun close() = socket.cancel()
                        }
                    }, characterPlatform = CharacterPlatform(true, loader::clear, loader::load))
                try {
                    repo.attachCharacter(renderer)
                    repeat(4) { cycle ->
                        repo.activityResumed(true); repo.foreground(true).join()
                        withTimeout(5000) { repo.state.first { it.phase == ConnectionPhase.SYNCING } }
                        SessionFixtures.frames().forEach(sockets.last()::offer)
                        sockets.last().offer(ExtensionsReady(1, ConnectionRepositoryTest.epoch, audioId(250 + cycle), listOf("character_v1")))
                        withTimeout(5000) { while (renderer.snapshots.size <= cycle) delay(10) }
                        repo.characterEvent(renderer, CharacterEvent("ready", snapshot.modelVersion, presentationGeneration = renderer.generation))
                        assertTrue(renderer.visible)
                        repo.activityResumed(false); repo.foreground(false).join()
                        assertTrue(repo.characterState.value.retainRenderer)
                    }
                    assertEquals(4, manifests.get()); assertEquals(1, assets.get()); assertEquals(0, renderer.clears)
                } finally {
                    repo.close(); loader.clear()
                }
            }
        }
    }
}
