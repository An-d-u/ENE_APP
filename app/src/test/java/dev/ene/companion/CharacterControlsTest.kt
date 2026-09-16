package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 편집 기준은 고정하고 성공/충돌/응답 유실 모두 새 manifest로 확정한다. */
class CharacterControlsTest {
    private val context = ExtensionContext(1, audioId(1), audioId(2), audioId(3))
    private fun snapshot(settingsRevision: Long = 1): CharacterSnapshot {
        val raw = Json.parseToJsonElement(CharacterFixtures.manifest()).jsonObject
        return CharacterSnapshot.parse(JsonObject(raw + ("settings_revision" to JsonPrimitive(settingsRevision))).toString())
    }
    private fun objectOf(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun controller() = CharacterControls { audioId(8) }.also { it.begin(snapshot()) }
    private fun answer(patch: CharacterSettingsPatch, status: String = "accepted", revision: Long = 2) =
        CharacterSettingsResult(1, audioId(1), audioId(2), patch.command_id, status, revision, "saved")

    @Test fun patchPinsModelRevisionAndOnlyIncludesRequestedSharedValues() {
        val controls = controller()
        val patch = controls.submit(context, objectOf("{\"enable_head_pat\":false}"), objectOf("{\"ParamAccent\":null}"))
        assertEquals(snapshot().modelVersion, patch.model_version)
        assertEquals(1L, patch.expected_revision)
        assertEquals(setOf("enable_head_pat"), patch.changes.keys)
        assertEquals(JsonNull, patch.parameters["ParamAccent"])
        assertEquals("sending", controls.phase)
    }

    @Test fun invalidFieldsRangesExpressionsOrUnknownParametersCannotBeSent() {
        val cases = listOf(
            "{\"window_x\":1}" to "{}", "{\"idle_motion_strength\":3}" to "{}",
            "{\"head_pat_active_emotion_custom\":\"missing\"}" to "{}",
            "{}" to "{\"Unknown\":null}", "{}" to "{\"ParamAccent\":2}",
            "{}" to "{\"ParamAccent\":true}", "{}" to "{\"ParamAccent\":\"0.2\"}",
        )
        for ((changes, parameters) in cases) {
            val controls = controller()
            assertThrows(IllegalArgumentException::class.java) { controls.submit(context, objectOf(changes), objectOf(parameters)) }
            assertEquals("editing", controls.phase)
        }
    }

    @Test fun currentExpressionAndExactParameterBoundsAreAllowed() {
        val controls = controller()
        val patch = controls.submit(context, objectOf("{\"head_pat_active_emotion_custom\":\"bright\",\"head_pat_end_emotion_custom\":\"\"}"),
            objectOf("{\"ParamAccent\":-1}"))
        assertEquals(-1.0, patch.parameters.getValue("ParamAccent").jsonPrimitive.double, .0001)
    }

    @Test fun secondSubmitIsBlockedUntilResultAndFreshSnapshot() {
        val controls = controller()
        val patch = controls.submit(context, objectOf("{}"), objectOf("{}"))
        assertThrows(IllegalArgumentException::class.java) { controls.submit(context, objectOf("{}"), objectOf("{}")) }
        assertThrows(IllegalArgumentException::class.java) { controls.begin(snapshot(2)) }
        assertTrue(controls.receive(answer(patch)))
        assertEquals("refreshing", controls.phase)
        assertFalse(controls.refresh(snapshot(1)))
        assertTrue(controls.refresh(snapshot(2)))
        assertEquals("editing", controls.phase)
        assertEquals(2L, controls.baseline!!.settingsRevision)
    }

    @Test fun conflictAlsoLoadsAuthoritativeSnapshotAndDoesNotRetryOldPatch() {
        val controls = controller()
        val patch = controls.submit(context, objectOf("{}"), objectOf("{}"))
        assertTrue(controls.receive(answer(patch, "conflict", 5)))
        assertEquals("conflict", controls.lastResult)
        assertFalse(controls.refresh(snapshot(4)))
        assertTrue(controls.refresh(snapshot(5)))
    }

    @Test fun oldConnectionOrDifferentCommandResultIsIgnored() {
        val controls = controller()
        val patch = controls.submit(context, objectOf("{}"), objectOf("{}"))
        assertFalse(controls.receive(answer(patch).copy(connection_generation = audioId(9))))
        assertFalse(controls.receive(answer(patch).copy(command_id = audioId(9))))
        assertEquals("sending", controls.phase)
    }

    @Test fun sendFailureRequiresFreshSnapshotAndNeverOffersAutomaticRetry() {
        val controls = controller()
        controls.submit(context, objectOf("{}"), objectOf("{}"))
        controls.deliveryUnknown()
        assertEquals("refreshing", controls.phase)
        assertEquals("unknown", controls.lastResult)
        assertThrows(IllegalArgumentException::class.java) { controls.submit(context, objectOf("{}"), objectOf("{}")) }
        assertTrue(controls.refresh(snapshot(1)))
        controls.reset()
        assertEquals("idle", controls.phase)
        assertNull(controls.baseline)
    }
}
