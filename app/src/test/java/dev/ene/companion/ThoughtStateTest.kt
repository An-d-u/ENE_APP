package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ThoughtStateTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val capabilities = listOf("message_thoughts_v1")
    private val ready = Ready(id(1), id(2), id(3), 1, capabilities)
    private var now = 0L
    private val messages = listOf(PublicMessage(id(5), "assistant", "가상 등대를 만들었습니다.", "2030-01-01T00:00:00Z"),
        PublicMessage(id(6), "assistant", "가상 지붕을 칠했습니다.", "2030-01-01T00:00:01Z"),
        PublicMessage(id(7), "user", "가상 입력", "2030-01-01T00:00:02Z"))
    private fun snapshot(revision: Long = 3) = ConversationSnapshot(id(2), id(3), id(9), revision, revision, messages, ProcessingState("idle"))
    private fun active() = ThoughtState(ready, { now }).also {
        it.receive(ExtensionsReady(1, id(2), id(4), capabilities))
        it.synchronize(snapshot()); it.visible(listOf(id(5), id(6), id(7)))
    }
    private fun response(query: ThoughtRequest, text: String = "가상 등대의 높이를 비교한다.") = ThoughtResponse(
        1, id(2), id(4), id(3), query.conversation_revision, query.query_id, query.message_id, "available", text)

    @Test fun visibleOldAnswersAreQueriedSequentiallyAndUserIsExcluded() {
        val state = active()
        val query = requireNotNull(state.nextRequest())
        assertEquals(id(5), query.message_id)
        assertNull(state.nextRequest())
        state.receive(response(query))
        assertEquals("available", state.entries[id(5)]?.status)
        now += 100
        val second = requireNotNull(state.nextRequest())
        assertEquals(id(6), second.message_id)
        state.receive(response(second)); now += 100
        assertNull(state.nextRequest())
        assertFalse(state.entries.containsKey(id(7)))
        assertFalse(state.entries[id(5)].toString().contains("등대"))
        assertFalse(response(query).toString().contains("등대"))
    }

    @Test fun replacementInvalidationAndDelayedResponsesNeverRestoreOldThought() {
        val state = active()
        val old = requireNotNull(state.nextRequest())
        state.synchronize(snapshot(4)); state.receive(response(old))
        assertTrue(state.entries.isEmpty())
        now += 100
        val newer = requireNotNull(state.nextRequest())
        state.receive(ThoughtInvalidated(1, id(2), id(4), id(3)))
        state.receive(response(newer)); assertTrue(state.entries.isEmpty())
        now += 100
        val current = requireNotNull(state.nextRequest())
        state.receive(response(current).copy(connection_generation = id(90)))
        assertTrue(state.entries.isEmpty())
        state.receive(response(current)); assertEquals(1, state.entries.size)
        state.pause(); assertTrue(state.entries.isEmpty()); assertNull(state.nextRequest())
        state.receive(response(current)); assertTrue(state.entries.isEmpty())
    }

    @Test fun unsupportedAndTimedOutQueriesDoNotLoop() {
        val unsupported = ThoughtState(ready.copy(capabilities = emptyList()), { now })
        unsupported.synchronize(snapshot()); unsupported.visible(listOf(id(5)))
        assertNull(unsupported.nextRequest())
        val state = active()
        val old = requireNotNull(state.nextRequest())
        now = 10_000
        state.visible(listOf(id(5)))
        assertNull(state.nextRequest())
        assertEquals("error", state.entries[id(5)]?.status)
        state.receive(response(old)); assertEquals("error", state.entries[id(5)]?.status)
        state.retry(id(5)); assertNotNull(state.nextRequest())
    }

    @Test fun scrollingDropsContentAndResetRejectsPriorConversation() {
        val state = active()
        val query = requireNotNull(state.nextRequest())
        state.receive(response(query))
        state.visible(listOf(id(6))); assertTrue(state.entries.isEmpty())
        state.synchronize(snapshot().copy(conversationId = id(80)))
        state.receive(response(query)); assertTrue(state.entries.isEmpty())
    }
}
