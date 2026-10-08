package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ChatActionStateTest.Companion.pair
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatDisplayRepositoryTest {
    @Test fun chatOnlyQueriesThenRecoversAndOldPcDropsPreviousEnabledValue() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_display_v1") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2).forEach(transport.socket::offer); runCurrent()
            advanceTimeBy(251); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<ChatDisplayRequest>().size)
            advanceTimeBy(5000); runCurrent()
            assertEquals("error", repo.state.value.chatDisplay.status)
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            val answer = ChatDisplaySettings(1, epoch, id(40), 3, true)
            transport.socket.offer(answer); runCurrent()
            assertTrue(repo.state.value.chatDisplay.splitEnabled)
            repo.activityResumed(true); runCurrent(); advanceTimeBy(251); runCurrent()
            assertEquals(2, transport.socket.sent.filterIsInstance<ChatDisplayRequest>().size)
            transport.socket.offer(answer); runCurrent()
            assertEquals("ready", repo.state.value.chatDisplay.status)
            assertEquals(pair, repo.state.value.messages)
            repo.foreground(false); runCurrent()
            assertTrue(repo.state.value.chatDisplay.splitEnabled)
            transport.capabilities = emptyList()
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2).forEach(transport.socket::offer); runCurrent()
            advanceTimeBy(6000); runCurrent()
            assertEquals(ChatDisplayViewState("unsupported", false), repo.state.value.chatDisplay)
            assertTrue(transport.socket.sent.none { it is ChatDisplayRequest })
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            repo.forget(); runCurrent()
            assertFalse(repo.state.value.chatDisplay.splitEnabled)
        } finally { repo.close(); runCurrent() }
    }
}
