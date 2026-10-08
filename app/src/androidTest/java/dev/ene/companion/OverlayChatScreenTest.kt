package dev.ene.companion

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ene.companion.character.CharacterViewState
import dev.ene.companion.connection.ConnectionViewState
import dev.ene.companion.connection.ChatDisplayViewState
import dev.ene.companion.protocol.PublicMessage
import dev.ene.companion.presentation.*
import dev.ene.companion.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 합성 화면으로 겹침·터치·뷰 소유권을 검사한다. 계측 실행 여부는 별도로 보고한다. */
class OverlayChatScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draggingAndMessagesDoNotResizeOrRecreateTheScene() {
        var layout by mutableStateOf(ChatLayoutState(loaded = true))
        var message by mutableStateOf("가상 응답 하나")
        var creates = 0; var releases = 0; var finishes = 0
        var scene: View? = null
        compose.setContent { MaterialTheme {
            OverlayChatLayout(layout, { layout = layout.copy(layout = ChatLayout(it)) }, { finishes++ },
                scene = { CharacterSurface(CharacterViewState("ready", retainRenderer = true, presentationAllowed = true),
                    true, true, { context -> View(context).also { scene = it; creates++ } }, { releases++ }) },
                toolbar = { Text("합성 제목") }, modifier = Modifier.fillMaxSize()) { Text(message) }
        } }
        var originalWidth = 0; var originalHeight = 0
        compose.runOnIdle { originalWidth = scene!!.width; originalHeight = scene!!.height }
        compose.onNodeWithContentDescription("대화창 높이").performTouchInput { swipe(center, center + Offset(0f, -120f), 400) }
        compose.runOnIdle {
            assertTrue(layout.layout.heightFraction > .46f)
            assertTrue(finishes > 0)
            message = "가상 응답 둘"
        }
        compose.onNodeWithContentDescription("대화창 높이").performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
        compose.runOnIdle {
            assertEquals(.8f, layout.layout.heightFraction, .001f)
            assertEquals(1, creates); assertEquals(0, releases)
            assertEquals(originalWidth, scene!!.width); assertEquals(originalHeight, scene!!.height)
        }
    }

    @Test fun emptyPanelSpaceShieldsCharacterTouch() {
        var touches = 0
        compose.setContent { MaterialTheme {
            OverlayChatLayout(ChatLayoutState(loaded = true), {}, {}, scene = {
                CharacterSurface(CharacterViewState("ready", retainRenderer = true, presentationAllowed = true), true, true,
                    { context -> View(context).apply { setOnTouchListener { _, _ -> touches++; true } } }, {})
            }, toolbar = { Text("합성 제목") }) { Spacer(Modifier.weight(1f)) }
        } }
        compose.onNodeWithTag("overlay-chat-panel").performTouchInput { click(Offset(center.x, height - 20f)) }
        compose.runOnIdle { assertEquals(0, touches) }
    }

    @Test fun displaySettingDoesNotRecreateCharacterOrChangeSceneBounds() {
        var split by mutableStateOf(false)
        var creates = 0
        var scene: View? = null
        val message = PublicMessage("synthetic-layout", "assistant", "가상 부품 하나.\n가상 부품 둘.", "2030-01-01T00:00:00Z")
        compose.setContent { MaterialTheme {
            OverlayChatLayout(ChatLayoutState(loaded = true), {}, {}, scene = {
                CharacterSurface(CharacterViewState("ready", retainRenderer = true, presentationAllowed = true), true, true,
                    { context -> View(context).also { scene = it; creates++ } }, {})
            }, toolbar = { Text("합성 제목") }) {
                ChatHistory(ConnectionViewState(messages = listOf(message), chatDisplay = ChatDisplayViewState("ready", split)),
                    Modifier.weight(1f), {}, {})
            }
        } }
        var width = 0
        var height = 0
        compose.runOnIdle { width = scene!!.width; height = scene!!.height; split = true }
        compose.onNodeWithText("가상 부품 하나.").assertExists()
        compose.runOnIdle { assertEquals(1, creates); assertEquals(width, scene!!.width); assertEquals(height, scene!!.height) }
    }

    @Test fun menuRetainsActionsAndDismissesBeforeOpeningDestination() {
        var action: String? = null
        compose.setContent { MaterialTheme {
            CompanionToolbar("연결됨", true, false, { action = "placement" }, { action = it })
        } }
        compose.onNodeWithContentDescription("연결 및 앱 설정").assertHeightIsAtLeast(48.dp).performClick()
        for (label in listOf("QR 연결", "PC 주소 수정", "연결 진단", "휴대폰 배치", "캐릭터 공통 설정", "등록 해제")) {
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("캐릭터 공통 설정").assertIsNotEnabled()
        compose.onNodeWithText("연결 진단").performClick()
        compose.onNodeWithText("QR 연결").assertDoesNotExist()
        compose.runOnIdle { assertEquals("diagnostics", action) }
        compose.onNodeWithText("배치").performClick()
        compose.runOnIdle { assertEquals("placement", action) }
    }
}
