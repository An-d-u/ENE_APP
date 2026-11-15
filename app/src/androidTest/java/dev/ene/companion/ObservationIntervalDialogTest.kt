package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ene.companion.connection.ObservationIntervals
import dev.ene.companion.connection.ObservationIntervalsView
import dev.ene.companion.ui.ObservationIntervalDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ObservationIntervalDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editsSharedValuesAndWaitsForConfirmation() {
        val state = mutableStateOf(ObservationIntervalsView(supported = true, loaded = true))
        var submitted: ObservationIntervals? = null
        compose.setContent {
            MaterialTheme {
                ObservationIntervalDialog(state.value, true, { sample, request ->
                    submitted = ObservationIntervals(sample, request)
                    state.value = state.value.copy(saving = true)
                }, {}, {})
            }
        }
        val inputs = compose.onAllNodes(hasSetTextAction())
        val apply = compose.onNodeWithText("적용")
        apply.assertIsNotEnabled()
        inputs[0].performTextReplacement("0")
        apply.assertIsNotEnabled()
        inputs[0].performTextReplacement("12")
        compose.runOnIdle { state.value = state.value.copy(intervals = ObservationIntervals(30, 240)) }
        inputs[1].assertTextContains("240")
        apply.performClick()
        compose.runOnIdle { assertEquals(ObservationIntervals(12, 240), submitted) }
        apply.assertIsNotEnabled()
        compose.onNodeWithText("PC에 저장 중…").assertExists()
        compose.runOnIdle { state.value = state.value.copy(intervals = submitted!!, saving = false) }
        compose.onNodeWithText("PC와 휴대폰에 적용했습니다.").assertExists()
    }

    @Test fun disconnectedDialogDoesNotShowUnconfirmedValues() {
        compose.setContent {
            MaterialTheme { ObservationIntervalDialog(ObservationIntervalsView(), false, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithText("PC ENE에 연결하면 간격을 조절할 수 있습니다.").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
