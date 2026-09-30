package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.ui.*
import org.junit.Assert.*
import org.junit.Test

class ChatActionPresentationTest {
    @Test fun oldPcShowsCompatibilityNoticeOnlyAfterConnection() {
        assertNull(chatActionCompatibilityNotice(ConnectionViewState()))
        val connected = ConnectionViewState(phase = ConnectionPhase.CONNECTED)
        assertTrue(requireNotNull(chatActionCompatibilityNotice(connected)).contains("업데이트"))
        assertNull(chatActionCompatibilityNotice(connected.copy(chatActions = ChatActionsViewState(supported = true))))
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
        assertEquals("변경된 대화를 확인하고 있습니다.", chatActionProgress(ChatActionsViewState(progress = "confirming"), "responding"))
        assertEquals("생각 중…", chatActionProgress(ChatActionsViewState(), "responding"))
        assertNull(chatActionProgress(ChatActionsViewState(), "idle"))
    }
    @Test fun editorAndActionsNeverPersistOrLogText() {
        for (path in listOf("ui/ChatMessageActions.kt", "connection/ChatActionState.kt")) {
            val source = java.io.File("src/main/java/dev/ene/companion/$path").readText()
            for (forbidden in listOf("rememberSaveable", "SavedStateHandle", "Log.", "println(", "SharedPreferences"))
                assertFalse(source.contains(forbidden))
        }
    }
}
