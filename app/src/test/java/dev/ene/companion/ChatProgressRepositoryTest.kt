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
class ChatProgressRepositoryTest {
    private class HeldDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        var held = false
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (held) tasks.add(block) else block.run()
        }
        fun drain() { held = false; while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun newConversationHeaderBlocksStaleDecodeAndProgress() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_progress_v1") }
        val dispatcher = StandardTestDispatcher(testScheduler); val decode = HeldDispatcher()
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, decode, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2, processing = ProcessingState("responding", id(8))).forEach(transport.socket::offer)
            transport.socket.offer(ChatProgress(1, epoch, id(40), conversation, 1, "generating", id(8))); runCurrent()
            assertEquals("generating", repo.state.value.chatProgress.phase)
            decode.held = true
            val appended = SessionFixtures.message("가상 늦은 메시지")
            transport.socket.offer(ChatEvent(epoch, conversation, 5, 3, "append", SessionFixtures.payload(appended)))
            runCurrent()
            transport.socket.offer(ResyncRequired(epoch, id(50), "reset"))
            SessionFixtures.frames(emptyList(), epoch = epoch, conversation = id(50)).forEach(transport.socket::offer)
            transport.socket.offer(ChatProgress(1, epoch, id(40), conversation, 2, "text_ready", id(8))); runCurrent()
            assertNull(repo.state.value.chatProgress.phase)
            decode.tasks.removeFirst().run(); runCurrent()
            assertEquals(pair, repo.state.value.messages)
            assertEquals(conversation, repo.state.value.conversationScope!!.conversationId)
            decode.drain(); runCurrent()
            assertEquals(id(50), repo.state.value.conversationScope!!.conversationId)
            assertTrue(repo.state.value.messages.isEmpty())
            assertEquals(id(50), transport.socket.sent.filterIsInstance<ChatProgressRequest>().last().conversation_id)
        } finally { decode.drain(); repo.close(); runCurrent() }
    }

    @Test fun frameConsumedAfterCloseCannotReviveGenerationOrPublishNewSnapshot() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_progress_v1") }
        val dispatcher = StandardTestDispatcher(testScheduler); val decode = HeldDispatcher()
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, decode, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2, processing = ProcessingState("responding", id(8))).forEach(transport.socket::offer)
            transport.socket.offer(ChatProgress(1, epoch, id(40), conversation, 1, "generating", id(8))); runCurrent()
            decode.held = true
            transport.socket.offer(ChatEvent(epoch, conversation, 5, 3, "append", SessionFixtures.payload(SessionFixtures.message())))
            runCurrent()
            transport.socket.incoming.close(); runCurrent()
            assertNull(repo.state.value.chatProgress.phase)
            decode.drain(); runCurrent()
            assertNull(repo.state.value.chatProgress.phase)
            assertEquals(pair, repo.state.value.messages)
        } finally { decode.drain(); repo.close(); runCurrent() }
    }

    @Test fun queryWaitsForSyncAndTextReadyKeepsSendLockedThenTimeoutRecovers() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_progress_v1") }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        try {
            repo.foreground(true); runCurrent(); advanceTimeBy(251); runCurrent()
            assertTrue(transport.socket.sent.none { it is ChatProgressRequest })
            assertTrue("chat_progress_v1" in transport.socket.sent.filterIsInstance<Hello>().single().capabilities)
            SessionFixtures.frames(pair, 4, 2, processing = ProcessingState("responding", id(8))).forEach(transport.socket::offer)
            runCurrent(); advanceTimeBy(251); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<ChatProgressRequest>().size)
            advanceTimeBy(5000); runCurrent()
            assertEquals("error", repo.state.value.chatProgress.status)
            assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
            transport.socket.offer(ChatProgress(1, epoch, id(40), conversation, 1, "text_ready", id(8))); runCurrent()
            repo.editDraft("가상 새 입력"); runCurrent()
            assertEquals("text_ready", repo.state.value.chatProgress.phase)
            assertFalse(repo.state.value.canSend)
            assertEquals(conversation, repo.state.value.conversationScope!!.conversationId)
            assertEquals(2L, repo.state.value.conversationRevision)
            repo.foreground(false); runCurrent()
            assertNull(repo.state.value.chatProgress.phase)
            transport.capabilities = emptyList()
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(pair, 4, 2).forEach(transport.socket::offer); runCurrent()
            assertEquals("unsupported", repo.state.value.chatProgress.status)
            repo.forget(); runCurrent()
            assertNull(repo.state.value.conversationScope)
        } finally { repo.close(); runCurrent() }
    }
}
