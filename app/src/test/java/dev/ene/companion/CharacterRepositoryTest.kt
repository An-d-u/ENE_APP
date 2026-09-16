package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterRepositoryTest {
    private class Media : CharacterMedia {
        var closed = false
        override suspend fun manifest(): ByteArray = error("시험 로더 사용")
        override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) = error("시험 로더 사용")
        override fun close() { closed = true }
    }
    private class Transport(val capabilities: List<String> = listOf("audio_pcm_v1", "character_v1")) : ConnectionTransport {
        val socket = ConnectionRepositoryTest.Socket()
        val audio = TestAudioMedia()
        val character = Media()
        var current: () -> Boolean = { false }
        var closed = false
        override val supportsAudio = true
        override val supportsCharacter = true
        override suspend fun info(endpoint: Endpoint) = ServerInfo(ConnectionRepositoryTest.serverId, listOf(1))
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
            socket.offer(Ready(expectedServerId, audioId(1), audioId(3), 1, capabilities))
            return socket
        }
        override fun audio(token: String, context: ExtensionContext, isCurrent: () -> Boolean): AudioMedia = audio
        override fun character(token: String, context: ExtensionContext, isCurrent: () -> Boolean): CharacterMedia {
            current = isCurrent
            return character
        }
        override fun close() { closed = true; socket.cancel() }
        fun snapshot() {
            socket.offer(ExtensionsReady(1, audioId(1), audioId(2), capabilities))
            SessionFixtures.frames(listOf(SessionFixtures.message().copy(id = audioId(4), role = "assistant")),
                epoch = audioId(1), conversation = audioId(3)).forEach(socket::offer)
        }
    }

    private fun TestScope.repository(transport: Transport, character: CharacterPlatform): ConnectionRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ConnectionRepository(ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials()),
            ConnectionRepositoryTest.Profiles(), transportFactory = { transport }, dispatcher = dispatcher,
            ioDispatcher = dispatcher, decodeDispatcher = dispatcher, nowMillis = { testScheduler.currentTime },
            audioPlatform = AudioTestPlatform(), characterPlatform = character)
    }

    @Test fun negotiatedCharacterDoesNotBreakAudioReadyAndDownloadFailureLeavesChatUsable() = runTest {
        val transport = Transport()
        val repo = repository(transport, CharacterPlatform(true) { _, _, _ -> throw CharacterException("synthetic_failure") })
        try {
            repo.activityResumed(true); repo.foreground(true); runCurrent()
            assertEquals(transport.capabilities, transport.socket.sent.filterIsInstance<Hello>().single().capabilities)
            transport.snapshot(); advanceTimeBy(300); runCurrent()
            assertTrue(transport.socket.sent.filterIsInstance<AudioAvailability>().last().available)
            assertEquals("error", repo.characterState.value.status)
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            repo.editDraft("합성 확인 문장"); runCurrent()
            assertTrue(repo.state.value.canSend)
            assertFalse(transport.socket.cancelled)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun unavailableSafeWebViewDoesNotAdvertiseCharacter() = runTest {
        val transport = Transport(listOf("audio_pcm_v1"))
        val repo = repository(transport, CharacterPlatform(false) { _, _, _ -> error("미지원 실행 금지") })
        try {
            repo.activityResumed(true); repo.foreground(true); runCurrent()
            assertEquals(listOf("audio_pcm_v1"), transport.socket.sent.filterIsInstance<Hello>().single().capabilities)
            transport.snapshot(); advanceTimeBy(300); runCurrent()
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun backgroundStopInvalidatesMediaAndJoinsLoaderBeforeClosingTransport() = runTest {
        val transport = Transport()
        var running = false
        val repo = repository(transport, CharacterPlatform(true) { _, _, _ ->
            running = true
            try { awaitCancellation() } finally { assertFalse(transport.closed); running = false }
        })
        try {
            repo.activityResumed(true); repo.foreground(true); runCurrent(); transport.snapshot()
            advanceTimeBy(300); runCurrent()
            assertTrue(running)
            assertTrue(transport.current())
            repo.activityResumed(false); repo.foreground(false); runCurrent()
            assertFalse(running)
            assertFalse(transport.current())
            assertTrue(transport.character.closed)
            assertTrue(transport.closed)
        } finally { repo.close(); runCurrent() }
    }
}
