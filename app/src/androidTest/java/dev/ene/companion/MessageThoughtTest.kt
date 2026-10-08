package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.PublicMessage
import dev.ene.companion.ui.ChatHistory
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import org.junit.Rule
import org.junit.Test

/** 합성 자료만 사용한다. 실제 기기 실행 여부는 빌드 결과와 구분한다. */
class MessageThoughtTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disclosureStartsCollapsedAndChangedContentCollapsesAgain() {
        var content by mutableStateOf(ThoughtContent("available", "가상 모자 색을 비교한다."))
        val message = PublicMessage("synthetic-thought", "assistant", "가상 응답 본문.", "2030-01-01T00:00:00Z")
        compose.setContent { MaterialTheme {
            ChatHistory(ConnectionViewState(messages = listOf(message), thoughts = mapOf(message.id to content)),
                Modifier.fillMaxSize(), {}, {})
        } }
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertDoesNotExist()
        compose.onNodeWithContentDescription("생각 보기").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertIsDisplayed()
        compose.onNodeWithContentDescription("생각 접기").performClick()
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertDoesNotExist()
        compose.onNodeWithContentDescription("생각 보기").performClick()
        compose.runOnIdle { content = ThoughtContent("available", "가상 모자 크기를 비교한다.") }
        compose.onNodeWithText("가상 모자 크기를 비교한다.").assertDoesNotExist()
        compose.onNodeWithContentDescription("생각 보기").assertExists()
        compose.runOnIdle { content = ThoughtContent("empty") }
        compose.onNodeWithContentDescription("생각 보기").assertDoesNotExist()
    }
}
