package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ChatActionStateTest.Companion.pair
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThoughtRepositoryTest {
    @Test fun visibleThoughtUsesNegotiatedSessionAndDisappearsOnInvalidationAndDisconnect() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("message_thoughts_v1") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2).forEach(transport.socket::offer); runCurrent()
            repo.visibleThoughts(listOf(id(6))); runCurrent(); advanceTimeBy(251); runCurrent()
            val query = transport.socket.sent.filterIsInstance<ThoughtRequest>().single()
            val answer = ThoughtResponse(1, epoch, id(40), conversation, 2, query.query_id, id(6), "available", "가상 지붕 모양을 비교한다.")
            transport.socket.offer(answer); runCurrent()
            assertEquals(answer.text, repo.state.value.thoughts[id(6)]?.text)
            transport.socket.offer(ThoughtInvalidated(1, epoch, id(40), conversation)); runCurrent()
            assertTrue(repo.state.value.thoughts.isEmpty())
            transport.socket.offer(answer); runCurrent(); assertTrue(repo.state.value.thoughts.isEmpty())
            repo.foreground(false); runCurrent()
            assertTrue(repo.state.value.thoughts.isEmpty())
        } finally { repo.close(); runCurrent() }
    }
}
