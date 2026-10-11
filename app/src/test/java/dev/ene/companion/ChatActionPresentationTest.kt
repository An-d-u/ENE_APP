package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.ui.*
import dev.ene.companion.protocol.ProcessingState
import org.junit.Assert.*
import org.junit.Test

class ChatActionPresentationTest {
    @Test fun oldPcShowsCompatibilityNoticeOnlyAfterConnection() {
        assertNull(chatActionCompatibilityNotice(ConnectionViewState()))
        val connected = ConnectionViewState(phase = ConnectionPhase.CONNECTED)
        assertTrue(requireNotNull(chatActionCompatibilityNotice(connected)).contains("업데이트"))
        assertNull(chatActionCompatibilityNotice(connected.copy(chatActions = ChatActionsViewState(supported = true))))
    }
    @Test fun displayNoticesAreSeparateFromConnectionAndActions() {
        assertNull(chatDisplayNotice(ConnectionViewState()))
        val state = ConnectionViewState(phase = ConnectionPhase.CONNECTED, chatDisplay = ChatDisplayViewState("unsupported"))
        assertTrue(requireNotNull(chatDisplayNotice(state)).contains("업데이트"))
        assertNull(chatDisplayNotice(state.copy(chatDisplay = ChatDisplayViewState("ready", true))))
        assertTrue(requireNotNull(chatDisplayNotice(state.copy(chatDisplay = ChatDisplayViewState("error", true)))).contains("마지막"))
    }
    @Test fun onlyExplicitLatestTargetsHaveControlsEvenWhileDisabled() {
        val state = ChatActionsViewState(supported = true, userMessageId = "latest-user", assistantMessageId = "latest-assistant")
        assertEquals("edit", messageAction("latest-user", "user", state))
        assertEquals("reroll", messageAction("latest-assistant", "assistant", state))
        assertNull(messageAction("older-user", "user", state))
        assertNull(messageAction("latest-user", "assistant", state))
        assertNull(messageAction("latest-user", "user", state.copy(supported = false)))
    }
    @Test fun unknownReasonsNeverEchoProviderOrUserContent() {
        val synthetic = "synthetic-private-provider-detail"
        assertFalse(chatActionReason(synthetic).contains(synthetic))
        assertTrue(chatActionReason("result_unknown").contains("자동"))
        assertTrue(chatActionReason("text_too_large").contains("16"))
    }
    @Test fun terminalConfirmationTakesPriorityOverProcessing() {
        val state = ConnectionViewState(phase = ConnectionPhase.CONNECTED, processing = ProcessingState("responding", "synthetic-request"))
        assertEquals("변경된 대화를 확인하고 있습니다.", chatActionProgress(state.copy(chatActions = ChatActionsViewState(progress = "confirming"))))
        assertEquals("처리 중…", chatActionProgress(state))
        assertNull(chatActionProgress(state.copy(processing = ProcessingState("idle"))))
    }
    @Test fun generationDisplayNeverUnlocksSendOrMistakesAudioForThinking() {
        val request = "synthetic-request"
        val state = ConnectionViewState(phase = ConnectionPhase.CONNECTED, draft = "가상 초안",
            processing = ProcessingState("responding", request), chatProgress = ChatProgressViewState("ready", "generating", request))
        assertEquals("생각 중…", chatActionProgress(state))
        assertEquals("요청을 준비하고 있습니다.", chatActionProgress(state.copy(chatProgress = state.chatProgress.copy(phase = "preparing"))))
        for (operation in listOf(null, "reserved", "accepted")) {
            val speech = state.copy(chatProgress = state.chatProgress.copy(phase = "text_ready"),
                chatActions = ChatActionsViewState(busy = operation != null, progress = operation, requestId = request))
            assertNull(chatActionProgress(speech))
            assertFalse(speech.canSend)
            assertFalse(speech.chatActions.canEdit); assertFalse(speech.chatActions.canReroll)
        }
        val retry = state.copy(chatActions = ChatActionsViewState(progress = "accepted", requestId = request))
        assertEquals("답변을 다시 생성하고 있습니다.", chatActionProgress(retry))
        assertEquals("처리 중…", chatActionProgress(state.copy(chatProgress = state.chatProgress.copy(requestId = "another-request"))))
        assertNull(chatActionProgress(state.copy(phase = ConnectionPhase.RECONNECTING)))
        assertTrue(requireNotNull(chatProgressNotice(state.copy(chatProgress = ChatProgressViewState("error")))).contains("유지"))
        assertNull(chatProgressNotice(state.copy(chatProgress = ChatProgressViewState("unsupported"))))
    }
    @Test fun editorAndActionsNeverPersistOrLogText() {
        for (path in listOf("ui/ChatMessageActions.kt", "connection/ChatActionState.kt")) {
            val source = java.io.File("src/main/java/dev/ene/companion/$path").readText()
            for (forbidden in listOf("rememberSaveable", "SavedStateHandle", "Log.", "println(", "SharedPreferences"))
                assertFalse(source.contains(forbidden))
        }
    }
}
