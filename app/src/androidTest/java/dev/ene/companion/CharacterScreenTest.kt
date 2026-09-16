package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ene.companion.character.CharacterViewState
import dev.ene.companion.ui.CharacterStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 표시/버튼 계약의 계측 코드는 컴파일만 한다. 실제 화면 수용 시험은 별도 단계다. */
class CharacterScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderFailureOffersExplicitRetryWithoutReconnectingChat() {
        var retries = 0
        compose.setContent { MaterialTheme { CharacterStatus(CharacterViewState("error", "character_renderer_gone"), false) { retries++ } } }
        compose.onNodeWithText("캐릭터를 표시하지 못했습니다. 채팅과 음성은 계속 사용할 수 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("캐릭터 다시 불러오기").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun collapsedCharacterExplainsWhyMessageInputTakesPriority() {
        compose.setContent { MaterialTheme { CharacterStatus(CharacterViewState("ready"), true) {} } }
        compose.onNodeWithText("입력 공간을 확보하기 위해 캐릭터 화면을 접었습니다.").assertIsDisplayed()
    }
}
