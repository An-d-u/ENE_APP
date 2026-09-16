package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ene.companion.ui.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 계측 코드는 컴파일만 검증한다. 실제 글자 확대·터치 수용 시험을 대신하지 않는다. */
class CharacterSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun labelledSwitchHasLargeTargetAndBusyStateBlocksSave() {
        var changes = 0
        compose.setContent { MaterialTheme {
            CharacterSettingRow(characterSettingFields.single { it.key == "enable_head_pat" },
                JsonPrimitive(true), emptyList(), false, { changes++ }, {})
        } }
        compose.onNodeWithContentDescription("쓰다듬기 사용").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun switchSendsOnlyOneExplicitChange() {
        var changes = 0
        var saves = 0
        compose.setContent { MaterialTheme {
            CharacterSettingRow(characterSettingFields.single { it.key == "enable_head_pat" },
                JsonPrimitive(true), emptyList(), true, { changes++ }, { saves++ })
        } }
        compose.onNodeWithContentDescription("쓰다듬기 사용").performClick()
        compose.runOnIdle { assertEquals(1, changes); assertEquals(1, saves) }
    }
}
