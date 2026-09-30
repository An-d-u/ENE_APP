package dev.ene.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 실제 단말 실행은 별도다. 합성 문장으로 화면 동작을 검증할 계측 시험이다. */
class ChatActionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val actions = ChatActionsViewState(supported = true, userMessageId = "u", assistantMessageId = "a", canEdit = true, canReroll = true)

    @Test fun latestControlsOnlyAndRecompositionDoesNotSendRequests() {
        var edits = 0; var rerolls = 0
        var state by mutableStateOf(actions)
        compose.setContent { MaterialTheme { Column {
            ChatMessageActions("old", "user", state, { edits++ }, { rerolls++ })
            ChatMessageActions("u", "user", state, { edits++ }, { rerolls++ })
            ChatMessageActions("a", "assistant", state, { edits++ }, { rerolls++ })
        } } }
        compose.onAllNodesWithText("수정").assertCountEquals(1)
        compose.onNodeWithText("수정").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("리롤").performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, rerolls); state = actions.copy(canReroll = false, rerollReason = "busy") }
        compose.onNodeWithText("리롤").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, rerolls) }
    }

    @Test fun retainedDraftIsVisibleAndStaleTargetCannotSubmitWithLargeText() {
        var submits = 0; var cancels = 0
        val draft = MessageEditor("u", "epoch", "conversation", 2, "가상 보존 초안", true, canSubmit = false, reason = "stale_target")
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                MessageEditDialog(draft, {}, { submits++ }, { cancels++ })
            }
        } }
        compose.onNodeWithText("가상 보존 초안").assertExists()
        compose.onNodeWithText("수정 후 재생성").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("취소").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, submits); assertEquals(1, cancels) }
    }

    @Test fun progressAppearsAfterLastMessage() {
        val messages = listOf(PublicMessage("u", "user", "가상 질문", "2030-01-01T00:00:00Z"),
            PublicMessage("a", "assistant", "가상 마지막 답변", "2030-01-01T00:00:01Z"))
        compose.setContent { MaterialTheme {
            ChatHistory(ConnectionViewState(messages = messages, chatActions = actions.copy(progress = "confirming", busy = true)), Modifier.fillMaxSize(), {}, {})
        } }
        val last = compose.onNodeWithText("가상 마지막 답변").fetchSemanticsNode().boundsInRoot
        val progress = compose.onNodeWithText("변경된 대화를 확인하고 있습니다.").fetchSemanticsNode().boundsInRoot
        assertTrue(progress.top >= last.bottom)
    }
}
