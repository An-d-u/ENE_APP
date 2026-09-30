package dev.ene.companion

import dev.ene.companion.connection.ChatActionState
import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

/** 조작 수명은 UI·파일·음성 객체 없이 합성 시간과 메시지로 검증한다. */
class ChatActionStateTest {
    @Test fun pendingActionAlsoLocksNormalSending() {
        val view = dev.ene.companion.connection.ConnectionViewState(
            phase = dev.ene.companion.connection.ConnectionPhase.CONNECTED, draft = "가상 새 메시지",
            chatActions = dev.ene.companion.connection.ChatActionsViewState(busy = true))
        assertFalse(view.canSend)
        assertTrue(view.copy(chatActions = dev.ene.companion.connection.ChatActionsViewState()).canSend)
    }
    companion object {
        fun id(number: Int) = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')
        val ready = Ready(id(1), id(2), id(3), 1, listOf("chat_actions_v1"))
        val extension = ExtensionsReady(1, id(2), id(4), listOf("chat_actions_v1"))
        val pair = listOf(PublicMessage(id(5), "user", "가상 주황색 원을 그려 줘.", "2030-01-01T00:00:00Z"),
            PublicMessage(id(6), "assistant", "가상의 원을 그렸습니다.", "2030-01-01T00:00:01Z"))
        fun snapshot(number: Int = 7, revision: Long = 2, seq: Long = 4) =
            ConversationSnapshot(id(2), id(3), id(number), seq, revision, pair, ProcessingState("idle"))
        fun availability(sequence: Long = 1, revision: Long = 2, seq: Long = 4, query: String? = null, snapshot: String? = null) =
            ChatActionsState(1, id(2), id(4), id(3), revision, seq, sequence, id(5), id(6), true, "ready", true, "ready", query, snapshot)
    }
    private var now = 0L
    private var next = 100
    private fun state(): ChatActionState = ChatActionState(nowMillis = { now }, idFactory = { id(next++) }).also {
        it.connected("synthetic-registration", ready)
        it.ready(extension)
    }
    private fun active() = state().also { it.snapshot(snapshot()); it.receiveState(availability()) }

    @Test fun onlyMatchingStateEnablesLastPairAndOldFramesCannotReplaceIt() {
        val state = state()
        state.receiveState(availability(2))
        assertFalse(state.view.canReroll)
        state.snapshot(snapshot())
        assertTrue(state.view.canEdit); assertTrue(state.view.canReroll)
        state.receiveState(availability(1).copy(reroll_allowed = false, reroll_reason = "busy"))
        assertTrue(state.view.canReroll)
        state.receiveState(availability(3).copy(connection_generation = id(99)))
        assertTrue(state.view.canReroll)
        assertNull(state.createReroll(id(90)))
    }

    @Test fun editDraftSurvivesFailureAndRequiresFreshMatchingSnapshot() {
        val state = active()
        state.openEditor(id(5)); state.editText("가상 원을 연두색으로 바꿔 줘.")
        val action = requireNotNull(state.createEdit())
        assertNull(state.createReroll(id(6)))
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "failed", id(5), "retry_failed"))
        val query = requireNotNull(state.nextQuery())
        assertTrue(query.refresh)
        state.snapshot(snapshot())
        state.receiveState(availability(2, query = query.query_id, snapshot = id(9)))
        assertTrue(state.view.busy)
        state.snapshot(snapshot(9))
        assertFalse(state.view.busy)
        assertEquals("가상 원을 연두색으로 바꿔 줘.", state.view.editor?.text)
        assertTrue(state.view.canReroll)
        state.openEditor(id(5))
        assertTrue(state.view.editor?.canSubmit == true)
    }

    @Test fun successClearsOnlyEditDraftAfterBarrier() {
        val state = active()
        state.openEditor(id(5))
        val action = requireNotNull(state.createEdit())
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "completed", id(5)))
        assertNotNull(state.view.editor)
        val query = requireNotNull(state.nextQuery())
        state.snapshot(snapshot(10, 4, 8))
        state.receiveState(availability(2, 4, 8, query.query_id, id(10)))
        assertNull(state.view.editor)
        assertFalse(state.view.busy)
    }

    @Test fun reconnectQueriesOriginalIdButUnknownNeverResendsAction() {
        val state = active()
        val action = requireNotNull(state.createReroll(id(6)))
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "accepted", id(5)))
        state.disconnected()
        state.connected("synthetic-registration", ready)
        state.ready(extension.copy(connection_generation = id(40)))
        assertEquals(listOf(action.request_id), state.syncRequest().pending_request_ids)
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "unknown"))
        val query = requireNotNull(state.nextQuery())
        assertTrue(query.refresh)
        state.snapshot(snapshot(11))
        state.receiveState(availability(3, query = query.query_id, snapshot = id(11)).copy(connection_generation = id(40)))
        assertFalse(state.view.busy)
        assertEquals("result_unknown", state.view.notice)
        assertNull(state.nextQuery())
    }

    @Test fun changedEpochKeepsInactiveDraftButNeverCarriesPendingAcross() {
        val state = active()
        state.openEditor(id(5)); state.editText("가상 별을 이동해 줘.")
        state.createEdit()
        state.disconnected(); state.connected("synthetic-registration", ready.copy(server_epoch = id(90)))
        assertTrue(state.syncRequest().pending_request_ids.isEmpty())
        assertEquals("가상 별을 이동해 줘.", state.view.editor?.text)
        assertFalse(state.view.editor?.canSubmit == true)
        state.forget(); assertNull(state.view.editor)
    }

    @Test fun changedRevisionNeverSilentlyRetargetsOpenEditor() {
        val state = active()
        state.openEditor(id(5)); state.editText("가상 수정 초안")
        state.snapshot(snapshot(12, 3, 5)); state.receiveState(availability(2, 3, 5))
        assertFalse(state.view.editor?.canSubmit == true)
        assertNull(state.createEdit())
        assertEquals("가상 수정 초안", state.view.editor?.text)
    }

    @Test fun lateQueryReplyAfterNewerAutomaticStateIsRetriedWithoutDeadlockOrFlood() {
        val state = active()
        val action = requireNotNull(state.createReroll(id(6)))
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "completed", id(5)))
        val query = requireNotNull(state.nextQuery())
        state.snapshot(snapshot(13))
        state.receiveState(availability(4))
        state.receiveState(availability(3, query = query.query_id, snapshot = id(13)))
        assertTrue(state.view.busy)
        assertNull(state.nextQuery())
        now += 500
        val replacement = requireNotNull(state.nextQuery())
        assertNotEquals(query.query_id, replacement.query_id)
        state.snapshot(snapshot(14))
        state.receiveState(availability(5, query = replacement.query_id, snapshot = id(14)))
        assertFalse(state.view.busy)
    }

    @Test fun limitsPrivateCommandsAndDraftToStringStaySafe() {
        val state = active()
        state.openEditor(id(5))
        for (value in listOf("   ", "/note synthetic", "가".repeat(5462))) {
            state.editText(value); assertFalse(state.view.editor?.canSubmit == true); assertNull(state.createEdit())
        }
        state.editText("가상의 분홍색 타원")
        assertFalse(state.view.toString().contains("분홍색"))
        assertFalse(state.view.editor.toString().contains("분홍색"))
        assertNotNull(state.createEdit())
    }

    @Test fun oldPcDisablesOnlyActionFeature() {
        val state = ChatActionState()
        state.connected("synthetic-registration", ready.copy(capabilities = emptyList()))
        state.snapshot(snapshot())
        assertFalse(state.view.supported)
        assertFalse(state.view.busy)
        assertNull(state.nextQuery())
    }

    @Test fun acceptedHasNoShortGenerationTimeoutButQueryDoes() {
        val state = active()
        val action = requireNotNull(state.createReroll(id(6)))
        assertTrue(state.awaitingResult)
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "accepted", id(5)))
        assertFalse(state.awaitingResult)
        now += 100_000
        state.checkTimeout(snapshotInProgress = false)
        state.receiveStatus(RequestStatus(id(2), id(3), action.request_id, "completed", id(5)))
        state.nextQuery(); now += 10_001
        assertThrows(IllegalStateException::class.java) { state.checkTimeout(snapshotInProgress = false) }
    }
}
