package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.serverId
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ChatActionStateTest.Companion.pair
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatActionRepositoryTest {
    class ChatTransport : ConnectionTransport {
        var socket = Socket()
        val sockets = mutableListOf<Socket>()
        var capabilities = listOf("chat_actions_v1")
        override suspend fun info(endpoint: Endpoint) = ServerInfo(serverId, listOf(1))
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
            if (socket.cancelled) socket = Socket()
            sockets.add(socket)
            socket.offer(Ready(serverId, epoch, conversation, 1, capabilities))
            if (capabilities.isNotEmpty()) socket.offer(ExtensionsReady(1, epoch, id(40), capabilities))
            return socket
        }
        override fun close() { socket.cancel() }
    }
    private fun TestScope.repo(transport: ChatTransport): ConnectionRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, dispatcher, nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
    }
    private fun state(sequence: Long = 1, query: String? = null, snapshot: String? = null, revision: Long = 2, seq: Long = 4) =
        ChatActionsState(1, epoch, id(40), conversation, revision, seq, sequence, id(5), id(6), true, "ready", true, "ready", query, snapshot)
    private fun frames(messages: List<PublicMessage> = pair, revision: Long = 2, seq: Long = 4) =
        SessionFixtures.frames(messages, seq, revision)
    private fun TestScope.initialize(repo: ConnectionRepository, transport: ChatTransport) {
        repo.foreground(true); runCurrent()
        frames().forEach(transport.socket::offer)
        transport.socket.offer(state()); runCurrent()
        assertTrue(repo.state.value.chatActions.canEdit)
    }

    @Test fun chatOnlyNegotiatesAndEditLocksBothActionsAndOrdinarySend() = runTest {
        val transport = ChatTransport(); val repo = repo(transport)
        try {
            initialize(repo, transport)
            assertEquals(listOf("chat_actions_v1", "message_thoughts_v1", "chat_display_v1"), transport.socket.sent.filterIsInstance<Hello>().single().capabilities)
            repo.editDraft("가상 별도의 새 초안")
            repo.openMessageEditor(id(5)); repo.editMessageDraft("가상 편집 초안")
            repo.submitMessageEdit(); repo.submitMessageEdit(); repo.rerollMessage(id(6)); repo.sendDraft(); runCurrent()
            val sent = transport.socket.sent.filterIsInstance<ChatAction>().single()
            assertEquals("edit", sent.kind); assertEquals("가상 편집 초안", sent.text)
            assertTrue(transport.socket.sent.none { it is SendText })
            assertEquals("가상 별도의 새 초안", repo.state.value.draft)
            assertFalse(repo.state.value.canSend)
            transport.socket.offer(RequestStatus(epoch, conversation, id(99), "completed")); runCurrent()
            assertTrue(repo.state.value.chatActions.busy)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun terminalRequiresFreshSnapshotEvenAfterEarlierSnapshotCompletes() = runTest {
        val transport = ChatTransport(); val repo = repo(transport)
        try {
            initialize(repo, transport)
            repo.rerollMessage(id(6)); runCurrent()
            val sent = transport.socket.sent.filterIsInstance<ChatAction>().single()
            transport.socket.offer(RequestStatus(epoch, conversation, sent.request_id, "completed", id(5))); runCurrent()
            val query = transport.socket.sent.filterIsInstance<ChatActionsRequest>().last()
            assertTrue(query.refresh)
            frames().forEach(transport.socket::offer); runCurrent()
            assertTrue(repo.state.value.chatActions.busy)
            val updated = pair.map { if (it.role == "assistant") it.copy(text = "가상 새 답변") else it }
            val fresh = frames(updated, 4, 8)
            fresh.forEach(transport.socket::offer)
            transport.socket.offer(state(3, query.query_id, (fresh.last() as SnapshotEnd).snapshot_id, 4, 8)); runCurrent()
            assertEquals("가상 새 답변", repo.state.value.messages.last().text)
            assertFalse(repo.state.value.chatActions.busy)
            assertTrue(repo.state.value.chatActions.canReroll)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun acceptedReconnectUnknownQueriesOriginalIdButNeverResendsAction() = runTest {
        val transport = ChatTransport(); val repo = repo(transport)
        try {
            initialize(repo, transport)
            repo.rerollMessage(id(6)); runCurrent()
            val sent = transport.socket.sent.filterIsInstance<ChatAction>().single()
            transport.socket.offer(RequestStatus(epoch, conversation, sent.request_id, "accepted", id(5))); runCurrent()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            assertEquals(listOf(sent.request_id), transport.socket.sent.filterIsInstance<SyncRequest>().single().pending_request_ids)
            frames().forEach(transport.socket::offer)
            transport.socket.offer(RequestStatus(epoch, conversation, sent.request_id, "unknown")); runCurrent()
            val query = transport.socket.sent.filterIsInstance<ChatActionsRequest>().last()
            val fresh = frames(); fresh.forEach(transport.socket::offer)
            transport.socket.offer(state(5, query.query_id, (fresh.last() as SnapshotEnd).snapshot_id)); runCurrent()
            assertTrue(transport.socket.sent.none { it is ChatAction || it is SendText })
            assertEquals("result_unknown", repo.state.value.chatActions.notice)
            assertFalse(repo.state.value.chatActions.busy)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun ordinaryPendingBlocksRerollAndOldPcStillSendsText() = runTest {
        for (old in listOf(false, true)) {
            val transport = ChatTransport().apply { if (old) capabilities = emptyList() }; val repo = repo(transport)
            try {
                repo.foreground(true); runCurrent(); frames().forEach(transport.socket::offer)
                if (!old) transport.socket.offer(state())
                runCurrent(); repo.editDraft("가상 기본 전송"); repo.sendDraft(); repo.rerollMessage(id(6)); runCurrent()
                assertEquals(1, transport.socket.sent.filterIsInstance<SendText>().size)
                assertTrue(transport.socket.sent.none { it is ChatAction })
                assertFalse(repo.state.value.chatActions.canReroll)
            } finally { repo.close(); runCurrent() }
        }
    }

    @Test fun failedEditRestoresDraftAndMissingQueryRecoversWithoutReplay() = runTest {
        val transport = ChatTransport(); val repo = repo(transport)
        try {
            initialize(repo, transport)
            repo.openMessageEditor(id(5)); repo.editMessageDraft("가상 보존할 편집 초안"); repo.submitMessageEdit(); runCurrent()
            val action = transport.socket.sent.filterIsInstance<ChatAction>().single()
            transport.socket.offer(RequestStatus(epoch, conversation, action.request_id, "failed", id(5), "retry_failed")); runCurrent()
            val query = transport.socket.sent.filterIsInstance<ChatActionsRequest>().last()
            val fresh = frames(); fresh.forEach(transport.socket::offer)
            transport.socket.offer(state(6, query.query_id, (fresh.last() as SnapshotEnd).snapshot_id)); runCurrent()
            assertFalse(repo.state.value.chatActions.busy)
            repo.openMessageEditor(id(5)); runCurrent()
            assertEquals("가상 보존할 편집 초안", repo.state.value.chatActions.editor?.text)
            assertTrue(repo.state.value.chatActions.editor?.canSubmit == true)
            repo.cancelMessageEditor(); repo.rerollMessage(id(6)); runCurrent()
            val reroll = transport.socket.sent.filterIsInstance<ChatAction>().last()
            transport.socket.offer(RequestStatus(epoch, conversation, reroll.request_id, "completed", id(5))); runCurrent()
            frames().forEach(transport.socket::offer); runCurrent()
            advanceTimeBy(11_500); runCurrent()
            assertTrue(transport.sockets.size > 1)
            assertTrue(transport.socket.sent.none { it is ChatAction })
            assertEquals(listOf(reroll.request_id), transport.socket.sent.filterIsInstance<SyncRequest>().single().pending_request_ids)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun earlySnapshotHeaderBlocksClicksWhileDecodeIsHeld() = runTest {
        class HeldDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
            var held = false
            val tasks = ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (held) tasks.add(block) else block.run()
            }
            fun drain() { held = false; while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        }
        val transport = ChatTransport(); val dispatcher = StandardTestDispatcher(testScheduler); val decode = HeldDispatcher()
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, dispatcher,
            dispatcher, decode, nowMillis = { testScheduler.currentTime })
        try {
            initialize(repo, transport)
            decode.held = true
            transport.socket.offer(ResyncRequired(epoch, conversation, "replace")); frames().forEach(transport.socket::offer); runCurrent()
            repo.editDraft("가상 대기 초안"); repo.sendDraft(); repo.rerollMessage(id(6)); runCurrent()
            assertTrue(transport.socket.sent.none { it is ChatAction || it is SendText })
            assertFalse(repo.state.value.chatActions.canReroll)
            decode.drain(); runCurrent()
        } finally { decode.drain(); repo.close(); runCurrent() }
    }
}
