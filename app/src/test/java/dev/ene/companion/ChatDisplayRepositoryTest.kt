package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ConnectionRepositoryTest.Companion.pairingId
import dev.ene.companion.ConnectionRepositoryTest.Companion.serverId
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ChatActionStateTest.Companion.pair
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatDisplayRepositoryTest {
    @Test fun registrationReplacementAndRevocationDiscardDisplayCacheBeforeNextReady() = runTest {
        for (replace in listOf(false, true)) {
            val display = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_display_v1") }
            var selected: ConnectionTransport = display
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { selected }, dispatcher,
                dispatcher, dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
            // 다음 Ready 전에는 화면에 노출되지 않는 등록 캐시도 즉시 비워야 한다.
            val cache = ConnectionRepository::class.java.getDeclaredField("chatDisplay").apply { isAccessible = true }.get(repo) as ChatDisplayState
            try {
                repo.foreground(true); runCurrent()
                SessionFixtures.frames(pair).forEach(display.socket::offer)
                display.socket.offer(ChatDisplaySettings(1, epoch, id(40), 3, true)); runCurrent()
                assertTrue(cache.view.splitEnabled)
                if (replace) {
                    val pairing = Transport(); selected = pairing
                    repo.pair(ConnectionRepositoryTest().qr()); runCurrent()
                    val approved = credentials()
                    pairing.socket.offer(PairApproved(pairingId, serverId, approved.deviceId, 1, approved.token))
                    selected = Transport().apply { failure = "pc_unreachable" }
                    runCurrent()
                } else {
                    repo.foreground(false); runCurrent()
                    selected = Transport().apply { failure = "authorization_revoked" }
                    repo.foreground(true); runCurrent()
                }
                assertFalse("등록 경계 이후 캐시: replace=$replace", cache.view.splitEnabled)
            } finally { repo.close(); runCurrent() }
        }
    }

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
