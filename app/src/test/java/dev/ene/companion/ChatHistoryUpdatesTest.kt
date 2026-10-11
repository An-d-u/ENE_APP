package dev.ene.companion

import dev.ene.companion.connection.ConversationScope
import dev.ene.companion.presentation.*
import dev.ene.companion.protocol.PublicMessage
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryUpdatesTest {
    private val scope = ConversationScope("registration", "epoch", "conversation")
    private val question = PublicMessage("question", "user", "가상의 질문", "2030-01-01T00:00:00Z")
    private val answer = question.copy(id = "answer", role = "assistant", text = "첫 번째 예시 답변")
    private fun ChatHistoryUpdates.layout(full: Set<String> = emptySet(), bottom: Boolean = false) =
        HistoryViewport(renderVersion, full, bottom)
    private fun reading(): ChatHistoryUpdates = ChatHistoryUpdates().apply {
        completedSnapshot(scope, 1, listOf(question, answer))
        measured(layout(bottom = true))
        userScrollStarted()
        userScrollFinished(layout())
    }

    @Test fun initialHistoryAndGrowthWhileFollowingHaveNoUnread() {
        val state = ChatHistoryUpdates()
        assertTrue(state.completedSnapshot(scope, 1, listOf(question)))
        assertEquals(HistoryMove.LATEST, state.move)
        state.completedSnapshot(scope, 2, listOf(question, answer))
        state.measured(state.layout())
        assertTrue(state.following)
        assertFalse(state.hasUnseen)
        assertEquals(HistoryMove.LATEST, state.contentOrViewportChanged())
    }

    @Test fun arrivalOutsideReadingViewportWaitsForCurrentMeasurement() {
        val state = reading()
        val old = state.layout(setOf(question.id, answer.id), bottom = true)
        state.completedSnapshot(scope, 2, listOf(question, answer, answer.copy(id = "next")))
        state.measured(old)
        state.userScrollFinished(old)
        assertFalse(state.hasUnseen)
        assertFalse(state.following)
        state.measured(state.layout(setOf(question.id)))
        assertTrue(state.hasUnseen)
        assertEquals(HistoryMove.ANCHOR, state.move)
    }

    @Test fun sameIdReplacementIsReadOnlyWhenEntireNewMessageIsVisible() {
        for (fullyVisible in listOf(false, true)) {
            val state = reading()
            val old = state.layout(setOf(answer.id))
            state.completedSnapshot(scope, 2, listOf(question, answer.copy(text = "바뀐 예시 답변")))
            state.measured(old)
            assertFalse(state.hasUnseen)
            state.measured(state.layout(if (fullyVisible) setOf(answer.id) else emptySet()))
            assertEquals(!fullyVisible, state.hasUnseen)
        }
    }

    @Test fun metadataReorderRemovalAndLayoutChangesAreNotArrivals() {
        val state = reading()
        state.completedSnapshot(scope, 2, listOf(answer.copy(displayed_at = "2030-01-02T00:00:00Z", request_id = "request"), question))
        state.measured(state.layout())
        state.completedSnapshot(scope, 3, listOf(question))
        assertEquals(HistoryMove.ANCHOR, state.contentOrViewportChanged())
        state.measured(state.layout())
        assertFalse(state.hasUnseen)
    }

    @Test fun sameConversationReconnectRetainsBaselineAndUnseenUntilExplicitBottom() {
        val state = reading()
        val changed = listOf(question, answer.copy(text = "바뀐 예시 답변"))
        state.completedSnapshot(scope, 2, changed)
        state.measured(state.layout())
        assertTrue(state.hasUnseen)
        // 연결 중간 상태는 완성 스냅샷 이벤트로 넘기지 않는다.
        val version = state.renderVersion
        state.completedSnapshot(scope, 2, changed)
        assertEquals(version, state.renderVersion)
        state.completedSnapshot(scope, 2, changed.toList())
        state.measured(state.layout(bottom = true))
        assertTrue(state.hasUnseen)
        state.userScrollStarted()
        state.userScrollFinished(state.layout(bottom = true))
        assertTrue(state.following)
        assertFalse(state.hasUnseen)
    }

    @Test fun everyScopeComponentResetsBaselineAndJumpClearsUnread() {
        for (next in listOf(scope.copy(registrationId = "next"), scope.copy(serverEpoch = "next"), scope.copy(conversationId = "next"))) {
            val state = reading()
            state.completedSnapshot(scope, 2, listOf(question, answer.copy(text = "바뀐 예시 답변")))
            state.measured(state.layout())
            assertTrue(state.completedSnapshot(next, 0, listOf(question)))
            assertTrue(state.following)
            assertFalse(state.hasUnseen)
        }
        val state = reading()
        state.completedSnapshot(scope, 2, listOf(question, answer.copy(text = "바뀐 예시 답변")))
        state.measured(state.layout())
        state.jumpToLatest()
        assertTrue(state.following)
        assertFalse(state.hasUnseen)
        state.forget()
        assertTrue(state.completedSnapshot(scope, 0, listOf(question)))
    }

    @Test fun rapidChangesCoalesceWithoutDroppingEarlierUnseenChanges() {
        val state = reading()
        state.completedSnapshot(scope, 2, listOf(question, answer.copy(text = "바뀐 예시 답변")))
        val intermediate = state.layout(setOf(answer.id))
        state.contentOrViewportChanged()
        state.completedSnapshot(scope, 3, listOf(question, answer.copy(text = "바뀐 예시 답변"), answer.copy(id = "next")))
        state.measured(intermediate)
        state.measured(state.layout(setOf("next")))
        assertTrue(state.hasUnseen)
        state.measured(state.layout(setOf(answer.id, "next")))
        assertTrue(state.hasUnseen)
    }

    @Test fun userStartCancelsFollowingButLayoutGrowthDoesNot() {
        val state = ChatHistoryUpdates()
        state.completedSnapshot(scope, 1, listOf(answer))
        state.measured(state.layout(bottom = false))
        assertTrue(state.following)
        state.userScrollStarted()
        assertFalse(state.following)
        assertTrue(state.userScrolling)
        state.userScrollFinished(state.layout(bottom = false))
        assertFalse(state.userScrolling)
        assertFalse(state.following)
    }

    @Test fun roleChangesCountButStaleRevisionCannotReplaceBaseline() {
        val state = reading()
        state.completedSnapshot(scope, 2, listOf(question.copy(role = "assistant"), answer))
        state.measured(state.layout())
        assertTrue(state.hasUnseen)
        state.jumpToLatest()
        val version = state.renderVersion
        state.completedSnapshot(scope, 1, listOf(question))
        assertEquals(version, state.renderVersion)
    }

    @Test fun arrivalDuringUserMovementCannotResumeFollowingFromOldBottomMeasurement() {
        val state = reading()
        state.userScrollStarted()
        val oldBottom = state.layout(bottom = true)
        state.completedSnapshot(scope, 2, listOf(question, answer.copy(text = "바뀐 예시 답변")))
        state.userScrollFinished(oldBottom)
        assertTrue(state.userScrolling)
        assertFalse(state.following)
        state.userScrollFinished(state.layout(bottom = false))
        assertFalse(state.userScrolling)
        assertFalse(state.following)
        assertTrue(state.hasUnseen)
    }
}
