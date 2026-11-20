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

    @Test fun sceneAndThoughtExpandIndependentlyAndChangedSummaryCollapses() {
        var content by mutableStateOf(ThoughtContent("available", "합성 공의 위치를 비교한다.", "PC 창에 합성 공 세 개가 보인다."))
        val message = PublicMessage("synthetic-scene", "assistant", "합성 응답입니다.", "2030-01-01T00:00:00Z")
        compose.setContent { MaterialTheme {
            ChatHistory(ConnectionViewState(messages = listOf(message), thoughts = mapOf(message.id to content)),
                Modifier.fillMaxSize(), {}, {})
        } }
        compose.onNodeWithContentDescription("화면 요약 보기").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("본 장면").assertIsDisplayed()
        compose.onNodeWithText("PC 창에 합성 공 세 개가 보인다.").assertIsDisplayed()
        compose.onNodeWithText("합성 공의 위치를 비교한다.").assertDoesNotExist()
        compose.onNodeWithContentDescription("생각 보기").performClick()
        compose.onNodeWithText("합성 공의 위치를 비교한다.").assertIsDisplayed()
        compose.onNodeWithContentDescription("화면 요약 접기").performClick()
        compose.onNodeWithText("PC 창에 합성 공 세 개가 보인다.").assertDoesNotExist()
        compose.onNodeWithText("합성 공의 위치를 비교한다.").assertIsDisplayed()
        compose.runOnIdle { content = ThoughtContent("empty", visualSummary = "후면 카메라에 합성 모형이 보인다.") }
        compose.onNodeWithContentDescription("생각 보기").assertDoesNotExist()
        compose.onNodeWithContentDescription("화면 요약 보기").performClick()
        compose.onNodeWithText("후면 카메라에 합성 모형이 보인다.").assertIsDisplayed()
        compose.runOnIdle { content = content.copy(visualSummary = "후면 카메라에 합성 모형 두 개가 보인다.") }
        compose.onNodeWithText("후면 카메라에 합성 모형 두 개가 보인다.").assertDoesNotExist()
        compose.onNodeWithContentDescription("화면 요약 보기").assertExists()
    }
}
