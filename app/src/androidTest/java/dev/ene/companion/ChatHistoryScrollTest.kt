package dev.ene.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ui.ChatHistory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 합성 이력만 사용한다. 이 소스의 컴파일은 실제 단말 실행과 다르다. */
class ChatHistoryScrollTest {
    @get:Rule val compose = createComposeRule()
    private fun message(index: Int) = PublicMessage("example-$index", if (index % 2 == 0) "user" else "assistant",
        "가상 블록 $index 의 모양을 비교하는 예시입니다.", "2030-01-01T00:00:00Z")
    private val scope = ConversationScope("example-registration", "example-epoch", "example-conversation")
    private var messages by mutableStateOf((0..30).map(::message))
    private var split by mutableStateOf(false)
    private var height by mutableStateOf(300.dp)
    private var large by mutableStateOf(false)
    private var revision by mutableLongStateOf(1)
    private lateinit var list: LazyListState

    private fun screen() = compose.setContent { MaterialTheme {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.8f else 1f)) {
            list = rememberLazyListState()
            ChatHistory(ConnectionViewState(phase = ConnectionPhase.CONNECTED, registered = true,
                messages = messages, conversationScope = scope, conversationRevision = revision,
                chatDisplay = ChatDisplayViewState("ready", split)),
                Modifier.width(320.dp).height(height), {}, {}, listState = list) {
                Text("가상 하단 안내")
            }
        }
    } }

    @Test fun followingTracksAddedAndSameKeyLongReplyThroughFooterAndHeightChanges() {
        screen()
        compose.runOnIdle { messages = messages + message(31); revision++ }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        compose.runOnIdle { messages = messages.dropLast(1) + message(31).copy(text = "가상 긴 답변. ".repeat(150)); revision++; height = 220.dp; large = true }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        compose.onNodeWithText("가상 하단 안내").assertIsDisplayed()
        compose.onNodeWithTag("chat-new-messages").assertDoesNotExist()
    }

    @Test fun readingAnchorSurvivesArrivalAndPillJumpsToActualBottom() {
        screen()
        compose.onNodeWithTag("chat-history").performScrollToIndex(8)
        compose.runOnIdle { messages = messages + message(31); revision++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(8, list.firstVisibleItemIndex) }
        compose.onNodeWithTag("chat-new-messages").assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        compose.onNodeWithTag("chat-new-messages").assertDoesNotExist()
    }

    @Test fun sameIdAndSameParagraphCountReplacementUsesNewBodyMeasurements() {
        messages = listOf(message(0), message(1), message(2).copy(text = "가상 후속 문단. ".repeat(100)))
        screen()
        compose.onNodeWithTag("chat-history").performScrollToIndex(1)
        compose.runOnIdle { messages = messages.map { if (it.id == "example-1") it.copy(text = "가상 교체 답변. ".repeat(150)) else it }; revision++ }
        compose.onNodeWithTag("chat-new-messages").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, list.firstVisibleItemIndex); assertTrue(list.canScrollForward) }
    }

    @Test fun rapidSplitArrivalAndUserDragRetainUserControl() {
        messages = listOf(message(0).copy(text = (0..150).joinToString("\n") { "가상 조각 $it" }))
        split = true
        screen()
        compose.onNodeWithTag("chat-history").performScrollToIndex(80)
        compose.runOnIdle { split = false; messages = messages + message(1); revision++ }
        compose.runOnIdle { messages = messages + message(2); revision++; split = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(80, list.firstVisibleItemIndex) }
        compose.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
        compose.runOnIdle { messages = messages + message(3); revision++ }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.canScrollForward); assertTrue(list.firstVisibleItemIndex < 80) }
        compose.onNodeWithTag("chat-new-messages").assertIsDisplayed()
    }

    @Test fun textReadyHidesThinkingWithoutUnlockingReroll() {
        val answer = message(1)
        compose.setContent { MaterialTheme {
            ChatHistory(ConnectionViewState(phase = ConnectionPhase.CONNECTED, messages = listOf(answer),
                processing = ProcessingState("responding", "example-request"),
                chatProgress = ChatProgressViewState("ready", "text_ready", "example-request"),
                chatActions = ChatActionsViewState(supported = true, assistantMessageId = answer.id, busy = true)),
                Modifier.width(320.dp).height(300.dp), {}, {})
        } }
        compose.onNodeWithText("생각 중…").assertDoesNotExist()
        compose.onNodeWithContentDescription("리롤").assertIsNotEnabled()
    }

    @Test fun userMoveDuringRestorationMeasurementCancelsOlderAnchor() {
        messages = listOf(message(0).copy(text = (0..150).joinToString("\n") { "가상 조각 $it" }))
        split = true
        screen()
        compose.onNodeWithTag("chat-history").performScrollToIndex(80)
        val move = compose.onNodeWithTag("chat-history").fetchSemanticsNode().config[SemanticsActions.ScrollToIndex].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { split = false }
        compose.mainClock.advanceTimeByFrame()
        // 복원 측정 대기를 끝내기 전에 사용자가 위치를 선택하고 새 답변이 도착한다.
        compose.runOnIdle {
            assertTrue(move(0))
            messages = messages + message(1)
            revision++
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            assertTrue(list.canScrollForward)
        }
        compose.onNodeWithTag("chat-new-messages").assertIsDisplayed()
    }
}
