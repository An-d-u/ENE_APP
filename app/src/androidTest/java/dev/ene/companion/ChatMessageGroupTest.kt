package dev.ene.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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

/** 합성 자료 전용. 계측 컴파일과 단말 실행 결과는 구분한다. */
class ChatMessageGroupTest {
    @get:Rule val compose = createComposeRule()
    private val message = PublicMessage("synthetic-answer", "assistant", "가상 육각형.\n가상 원통.", "2030-01-01T00:00:00Z")
    private val actions = ChatActionsViewState(supported = true, assistantMessageId = message.id, canReroll = true)

    @Test fun splitChangesOnlyBubblesAndKeepsThoughtExpandedAndOriginalActionId() {
        var split by mutableStateOf(false)
        var large by mutableStateOf(false)
        var target: String? = null
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.8f else 1f)) {
                ChatHistory(ConnectionViewState(messages = listOf(message), chatActions = actions,
                    chatDisplay = ChatDisplayViewState("ready", split),
                    thoughts = mapOf(message.id to ThoughtContent("available", "가상 입체의 모서리를 비교한다."))),
                    Modifier.width(if (large) 260.dp else 380.dp).fillMaxHeight(), {}, { target = it })
            }
        } }
        compose.onNodeWithContentDescription("생각 보기").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { split = true }
        compose.onNodeWithText("가상 육각형.").assertExists()
        compose.onNodeWithText("가상 원통.").assertExists()
        compose.onAllNodesWithContentDescription("리롤").assertCountEquals(1)
        compose.onAllNodesWithTag("message-time").assertCountEquals(1)
        compose.onNodeWithText("가상 입체의 모서리를 비교한다.").assertExists()
        compose.runOnIdle { large = true }
        compose.onNodeWithText("가상 입체의 모서리를 비교한다.").assertExists()
        compose.onNodeWithContentDescription("리롤").assertWidthIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(message.id, target) }
    }

    @Test fun maximumLineCountOnlyComposesVisibleRows() {
        compose.setContent { MaterialTheme {
            ChatHistory(ConnectionViewState(messages = listOf(message.copy(text = "x\n".repeat(524288))),
                chatActions = actions, chatDisplay = ChatDisplayViewState("ready", true)),
                Modifier.fillMaxSize(), {}, {})
        } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("chat-bubble").fetchSemanticsNodes().size < 100)
        compose.onNodeWithContentDescription("리롤").assertExists()
    }

    @Test fun middleParagraphAndBottomSurviveRepeatedSplitChanges() {
        var split by mutableStateOf(true)
        lateinit var list: LazyListState
        val text = (0..200).joinToString("\n") { "가상 항목 $it." }
        compose.setContent { MaterialTheme {
            list = rememberLazyListState()
            ChatHistory(ConnectionViewState(messages = listOf(message.copy(text = text)),
                chatDisplay = ChatDisplayViewState("ready", split)),
                Modifier.width(320.dp).height(300.dp), {}, {}, listState = list)
        } }
        compose.onNodeWithTag("chat-history").performScrollToIndex(120)
        repeat(3) {
            compose.runOnIdle { split = false }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(list.firstVisibleItemScrollOffset > 1000); split = true }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(120, list.firstVisibleItemIndex) }
            compose.onNodeWithText("가상 항목 120.").assertIsDisplayed()
        }
        compose.onNodeWithTag("chat-history").performScrollToIndex(201)
        compose.runOnIdle { split = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward); split = true }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
    }

    @Test fun responseArrivalDuringSplitRestorationKeepsOriginalReadingPosition() {
        var split by mutableStateOf(true)
        var messages by mutableStateOf(listOf(message.copy(text = (0..200).joinToString("\n") { "가상 항목 $it." })))
        var busy by mutableStateOf(true)
        lateinit var list: LazyListState
        compose.setContent { MaterialTheme {
            list = rememberLazyListState()
            ChatHistory(ConnectionViewState(messages = messages,
                chatActions = actions.copy(busy = busy), chatDisplay = ChatDisplayViewState("ready", split)),
                Modifier.width(320.dp).height(300.dp), {}, {}, listState = list)
        } }
        compose.onNodeWithTag("chat-history").performScrollToIndex(120)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { split = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            messages = messages + message.copy(id = "synthetic-next", text = "가상 다음 항목.")
            busy = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.firstVisibleItemScrollOffset > 1000); split = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(120, list.firstVisibleItemIndex) }
    }
}
