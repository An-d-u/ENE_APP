package dev.ene.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ene.companion.character.*
import dev.ene.companion.ui.CharacterPlacementContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 계측 소스 컴파일만으로 실제 단말 터치·큰 글자 시험을 통과했다고 판단하지 않는다. */
class CharacterPlacementScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slidersHaveLabelsAndWaitForRestore() {
        compose.setContent { MaterialTheme {
            CharacterPlacementContent(CharacterPlacementState(), {}, {}, {}, {}, {})
        } }
        for (label in listOf("캐릭터 크기", "가로 위치", "세로 위치")) {
            compose.onNodeWithContentDescription(label).assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun editsResetRetryAndCloseAreReachableWithLargeText() {
        var state by mutableStateOf(CharacterPlacementState(loaded = true, saveStatus = "error", readFailed = true))
        var finished = 0; var retries = 0; var resets = 0; var closes = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
                Box(Modifier.width(320.dp).height(420.dp)) {
                    CharacterPlacementContent(state, { state = state.copy(placement = it) }, { finished++ },
                        { state = state.copy(placement = CharacterPlacement()); resets++ }, { retries++ }, { closes++ })
                }
            }
        } }
        compose.onNodeWithContentDescription("캐릭터 크기").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "600%"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(600f, 50f..600f)))
        compose.onNodeWithContentDescription("가로 위치").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-300f) }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "-300%"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(-300f, -300f..400f)))
        compose.onNodeWithContentDescription("세로 위치").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(400f) }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "400%"))
        compose.runOnIdle { assertEquals(CharacterPlacement(6.0, -300.0, 400.0), state.placement); assertTrue(finished >= 3) }
        compose.onNodeWithText("저장 재시도").performScrollTo().performClick()
        compose.onNodeWithText("기본 배치 복원").performScrollTo().performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle {
            assertEquals(1, retries); assertEquals(1, resets); assertEquals(1, closes)
            assertEquals(CharacterPlacement(), state.placement)
        }
    }
}
