package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ThoughtContent
import dev.ene.companion.ui.MessageThought
import org.junit.Rule
import org.junit.Test

/** 합성 자료만 사용한다. 실제 기기 실행 여부는 빌드 결과와 구분한다. */
class MessageThoughtTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disclosureStartsCollapsedAndChangedContentCollapsesAgain() {
        var content by mutableStateOf(ThoughtContent("available", "가상 모자 색을 비교한다."))
        compose.setContent { MaterialTheme { MessageThought(content) {} } }
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertDoesNotExist()
        compose.onNodeWithText("생각 보기").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertIsDisplayed()
        compose.onNodeWithText("접기").performClick()
        compose.onNodeWithText("가상 모자 색을 비교한다.").assertDoesNotExist()
        compose.onNodeWithText("생각 보기").performClick()
        compose.runOnIdle { content = ThoughtContent("available", "가상 모자 크기를 비교한다.") }
        compose.onNodeWithText("가상 모자 크기를 비교한다.").assertDoesNotExist()
        compose.onNodeWithText("생각 보기").assertExists()
        compose.runOnIdle { content = ThoughtContent("empty") }
        compose.onNodeWithText("생각 보기").assertDoesNotExist()
    }
}
