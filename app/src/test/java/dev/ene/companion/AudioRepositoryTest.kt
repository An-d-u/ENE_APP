package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRepositoryTest {
    private class HeldDecode : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next() { if (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Transport : ConnectionTransport {
        val socket = ConnectionRepositoryTest.Socket()
        val media = TestAudioMedia()
        var current: () -> Boolean = { false }
        override val supportsAudio = true
        override suspend fun info(endpoint: Endpoint) = ServerInfo(ConnectionRepositoryTest.serverId, listOf(1))
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
            socket.offer(Ready(expectedServerId, audioId(1), audioId(3), 1, listOf("audio_pcm_v1")))
            return socket
        }
        override fun audio(token: String, context: ExtensionContext, isCurrent: () -> Boolean): AudioMedia {
            current = isCurrent
            return media
        }
        override fun close() { socket.cancel() }
    }

    @Test fun realRepositoryNegotiatesAudioAndPauseIsImmediateBeforeConnectionStop() = runTest {
        val transport = Transport()
        val platform = AudioTestPlatform()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConnectionRepository(
            ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials()), ConnectionRepositoryTest.Profiles(),
            transportFactory = { transport }, dispatcher = dispatcher, ioDispatcher = dispatcher, decodeDispatcher = dispatcher,
            nowMillis = { testScheduler.currentTime }, audioPlatform = platform,
        )
        try {
            repo.activityResumed(true)
            repo.foreground(true); runCurrent()
            assertEquals(listOf("audio_pcm_v1"), transport.socket.sent.filterIsInstance<Hello>().single().capabilities)
            transport.socket.offer(ExtensionsReady(1, audioId(1), audioId(2), listOf("audio_pcm_v1")))
            val message = SessionFixtures.message().copy(id = audioId(4), role = "assistant")
            SessionFixtures.frames(listOf(message), epoch = audioId(1), conversation = audioId(3)).forEach(transport.socket::offer)
            runCurrent()
            assertTrue(transport.socket.sent.filterIsInstance<AudioAvailability>().last().available)
            transport.socket.offer(AudioOffer(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 24000, 1, 2, 2000))
            runCurrent()
            transport.media.chunks.send(ByteArray(9600)); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<AudioPrepared>().size)
            transport.socket.offer(AudioStart(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6)))
            runCurrent()
            assertEquals("phone", repo.state.value.audioOutput)
            repo.activityResumed(false)
            assertEquals(1, platform.sinks.single().stops)
            assertFalse(transport.socket.cancelled)
            repo.foreground(false); runCurrent()
            assertFalse(transport.current())
            assertEquals(1, platform.sinks.single().releases)
            assertTrue(transport.media.done)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun deferredOldSnapshotCannotRestoreAvailabilityAfterNewConversationHeader() = runTest {
        val transport = Transport()
        val platform = AudioTestPlatform()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val decode = HeldDecode()
        val repo = ConnectionRepository(
            ConnectionRepositoryTest.Registrations(ConnectionRepositoryTest.credentials()), ConnectionRepositoryTest.Profiles(),
            transportFactory = { transport }, dispatcher = dispatcher, ioDispatcher = dispatcher, decodeDispatcher = decode,
            nowMillis = { testScheduler.currentTime }, audioPlatform = platform,
        )
        fun drainDecode() { while (decode.tasks.isNotEmpty()) { decode.next(); runCurrent() } }
        try {
            repo.activityResumed(true); repo.foreground(true); runCurrent()
            transport.socket.offer(ExtensionsReady(1, audioId(1), audioId(2), listOf("audio_pcm_v1")))
            val message = SessionFixtures.message().copy(id = audioId(4), role = "assistant")
            SessionFixtures.frames(listOf(message), epoch = audioId(1), conversation = audioId(3)).forEach(transport.socket::offer)
            runCurrent(); drainDecode()
            assertTrue(transport.socket.sent.filterIsInstance<AudioAvailability>().last().available)

            transport.socket.offer(ResyncRequired(audioId(1), audioId(3), "gap"))
            SessionFixtures.frames(listOf(message), epoch = audioId(1), conversation = audioId(3)).forEach(transport.socket::offer)
            runCurrent()
            // 옛 snapshot을 아직 해석하지 않은 동안 새 대화 헤더와 heartbeat를 먼저 읽는다.
            transport.socket.offer(ResyncRequired(audioId(1), audioId(30), "reset"))
            transport.socket.offer(Ping(audioId(90)))
            runCurrent()
            assertEquals(audioId(90), transport.socket.sent.filterIsInstance<Pong>().last().nonce)
            val mark = transport.socket.sent.size
            drainDecode()
            assertFalse(transport.socket.sent.drop(mark).filterIsInstance<AudioAvailability>().any { it.available })
            assertEquals(audioId(30), transport.socket.sent.filterIsInstance<AudioAvailability>().last().conversation_id)
            transport.socket.offer(AudioOffer(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 24000, 1, 2, 2000))
            runCurrent()
            assertTrue(platform.sinks.isEmpty())

            SessionFixtures.frames(listOf(message), epoch = audioId(1), conversation = audioId(30)).forEach(transport.socket::offer)
            runCurrent(); drainDecode()
            val available = transport.socket.sent.filterIsInstance<AudioAvailability>().last()
            assertTrue(available.available)
            assertEquals(audioId(30), available.conversation_id)
        } finally { repo.close(); runCurrent(); drainDecode() }
    }
}
