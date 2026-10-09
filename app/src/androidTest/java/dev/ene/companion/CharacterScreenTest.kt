package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ene.companion.character.CharacterViewState
import dev.ene.companion.ui.CharacterStatus
import dev.ene.companion.ui.CharacterSurface
import android.view.View
import androidx.compose.runtime.*
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 표시/버튼 계약의 계측 코드는 컴파일만 한다. 실제 화면 수용 시험은 별도 단계다. */
class CharacterScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsedAndSyncingSurfaceKeepsViewUntilRetryOrShutdown() {
        var shown by mutableStateOf(true)
        var state by mutableStateOf(CharacterViewState("ready", retainRenderer = true, presentationAllowed = true))
        var creations = 0
        var releases = 0
        var view: View? = null
        compose.setContent { MaterialTheme {
            CharacterSurface(state, true, shown, { context -> View(context).also { creations++; view = it } }, { releases++ })
        } }
        compose.runOnIdle { assertEquals(1, creations); shown = false }
        compose.runOnIdle {
            assertEquals(0, releases); assertEquals(View.INVISIBLE, view!!.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view!!.importantForAccessibility)
            state = state.copy(presentationAllowed = false)
        }
        compose.runOnIdle { shown = true; state = state.copy(presentationAllowed = true) }
        compose.runOnIdle {
            assertEquals(1, creations); assertEquals(0, releases); assertEquals(View.VISIBLE, view!!.visibility)
            state = state.copy(viewGeneration = 1)
        }
        compose.runOnIdle { assertEquals(2, creations); assertEquals(1, releases); state = state.copy(retainRenderer = false) }
        compose.runOnIdle { assertEquals(2, releases) }
    }

    @Test fun renderFailureOffersExplicitRetryWithoutReconnectingChat() {
        var retries = 0
        compose.setContent { MaterialTheme { CharacterStatus(CharacterViewState("error", "character_renderer_gone")) { retries++ } } }
        compose.onNodeWithText("캐릭터를 표시하지 못했습니다. 채팅과 음성은 계속 사용할 수 있습니다.").assertIsDisplayed()
        compose.onNodeWithText("캐릭터 다시 불러오기").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun retainedSurfaceSurvivesRepeatedReconnectsButNotActualDisposal() {
        var mounted by mutableStateOf(true)
        var state by mutableStateOf(CharacterViewState("ready", retainRenderer = true, presentationAllowed = true))
        var creations = 0
        var releases = 0
        compose.setContent { MaterialTheme {
            if (mounted) CharacterSurface(state, true, true, { View(it).also { creations++ } }, { releases++ })
        } }
        repeat(3) {
            compose.runOnIdle { state = state.copy(status = "refreshing", presentationAllowed = false) }
            compose.runOnIdle { assertEquals(1, creations); assertEquals(0, releases); state = state.copy(status = "ready", presentationAllowed = true) }
        }
        compose.runOnIdle { state = state.copy(viewGeneration = 1) }
        compose.runOnIdle { assertEquals(2, creations); assertEquals(1, releases); mounted = false }
        compose.runOnIdle { assertEquals(2, releases); mounted = true }
        compose.runOnIdle { assertEquals(3, creations); assertEquals(2, releases) }
    }

    @Test fun loadingCharacterKeepsItsStatusReachable() {
        compose.setContent { MaterialTheme { CharacterStatus(CharacterViewState("loading")) {} } }
        compose.onNodeWithText("PC의 캐릭터를 가져오고 있습니다.").assertIsDisplayed()
    }
}
