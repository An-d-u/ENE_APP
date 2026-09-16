package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ui.characterSettingFields
import dev.ene.companion.ui.characterSliderValue
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 미리보기와 저장 확정을 분리하고 응답 유실 때 명령을 재전송하지 않는다. */
class CharacterSettingsStateTest {
    private val context = ExtensionContext(1, audioId(1), audioId(2), audioId(3))
    private fun snapshot(revision: Long = 1, strength: Double = 1.0): CharacterSnapshot {
        val raw = Json.parseToJsonElement(CharacterFixtures.manifest()).jsonObject
        return CharacterSnapshot.parse(JsonObject(raw + mapOf(
            "settings_revision" to JsonPrimitive(revision), "state_revision" to JsonPrimitive(revision),
            "settings" to buildJsonObject { put("head_pat_strength", strength); put("enable_head_pat", true) },
        )).toString())
    }
    private class Fixture(context: ExtensionContext) {
        var now = 0L
        var loads = 0
        var delivered = true
        val sent = mutableListOf<CharacterSettingsPatch>()
        val shown = mutableListOf<CharacterSnapshot>()
        val editor = CharacterSettingsState(context, { sent += it; delivered }, shown::add, { loads++ }, { now })
    }
    private fun fixture() = Fixture(context).also { it.editor.install(snapshot()); it.editor.available(true); it.editor.open() }

    @Test fun optionalHeadPatDefaultsAreCatalogBoundAndOlderManifestsRemainValid() {
        val model = snapshot()
        val patched = JsonObject(model.json + ("head_pat_defaults" to buildJsonObject { put("active", "bright"); put("end", "normal") }))
        assertEquals("bright", CharacterSnapshot.parse(patched.toString()).json.getValue("head_pat_defaults").jsonObject.getValue("active").jsonPrimitive.content)
        val invalid = JsonObject(model.json + ("head_pat_defaults" to buildJsonObject { put("active", "missing"); put("end", "normal") }))
        assertThrows(CharacterException::class.java) { CharacterSnapshot.parse(invalid.toString()) }
    }
    private fun answer(patch: CharacterSettingsPatch, status: String = "accepted", revision: Long = 2) =
        CharacterSettingsResult(1, audioId(1), audioId(2), patch.command_id, status, revision, "saved")

    @Test fun dragOnlyPreviewsAndReleaseSendsOneDelta() {
        val f = fixture()
        repeat(30) { f.editor.preview("head_pat_strength", JsonPrimitive(1.0 + it / 100.0)) }
        assertTrue(f.sent.isEmpty())
        f.editor.submit(); f.editor.submit()
        assertEquals(1, f.sent.size)
        assertEquals(setOf("head_pat_strength"), f.sent.single().changes.keys)
        assertTrue(f.editor.view.busy)
    }

    @Test fun acceptedWaitsForFreshManifestAndConflictNeverRetries() {
        for (status in listOf("accepted", "conflict", "rejected")) {
            val f = fixture(); f.editor.preview("enable_head_pat", JsonPrimitive(false)); f.editor.submit()
            f.editor.receive(answer(f.sent.single(), status))
            assertEquals(1, f.loads); assertTrue(f.editor.view.busy)
            f.editor.install(snapshot())
            assertTrue(f.editor.view.busy)
            f.editor.install(snapshot(2, 1.8))
            assertFalse(f.editor.view.busy)
            assertEquals(status, f.editor.view.result)
            assertEquals(1.8, f.editor.view.snapshot!!.json.getValue("settings").jsonObject.getValue("head_pat_strength").jsonPrimitive.double, 0.001)
            assertEquals(1, f.sent.size)
        }
    }

    @Test fun closingRestoresLatestConfirmedSnapshotNotOpeningValues() {
        val f = fixture(); f.editor.preview("head_pat_strength", JsonPrimitive(2.0))
        f.editor.install(snapshot(2, 1.6)); f.editor.close()
        assertFalse(f.editor.view.open)
        assertEquals(1.6, f.shown.last().json.getValue("settings").jsonObject.getValue("head_pat_strength").jsonPrimitive.double, 0.001)
        assertTrue(f.sent.isEmpty())
    }

    @Test fun unknownDeliveryAndTimeoutLoadOnceWithoutRetry() {
        for (delivered in listOf(true, false)) {
            val f = fixture(); f.delivered = delivered
            f.editor.preview("enable_head_pat", JsonPrimitive(false)); f.editor.submit()
            f.now = 10_001; repeat(20) { f.editor.tick() }
            assertEquals(1, f.sent.size); assertEquals(1, f.loads)
            assertEquals("unknown", f.editor.view.result)
            assertTrue(f.editor.view.busy)
            f.editor.install(snapshot())
            assertFalse(f.editor.view.busy)
        }
    }

    @Test fun hiddenOrBusyEditorCannotSendAndPauseClearsPreview() {
        val f = fixture(); f.editor.preview("head_pat_strength", JsonPrimitive(2.0))
        f.editor.available(false); f.editor.submit()
        assertTrue(f.sent.isEmpty()); assertFalse(f.editor.view.open)
        f.editor.available(true); f.editor.open(); f.editor.submit()
        assertTrue(f.sent.isEmpty())
    }

    @Test fun staleManifestAndUnknownResultsCannotReplaceCurrentValues() {
        val f = fixture(); f.editor.install(snapshot(3, 1.7)); f.editor.install(snapshot(2, 1.2))
        f.editor.receive(CharacterSettingsResult(1, audioId(1), audioId(2), audioId(7), "accepted", 4, "saved"))
        assertEquals(3L, f.editor.view.snapshot!!.settingsRevision); assertEquals(0, f.loads)
    }

    @Test fun onlyValidSettingsAndEditableCatalogParametersCanPreview() {
        val f = fixture()
        for ((key, value) in listOf("window_x" to JsonPrimitive(1), "head_pat_strength" to JsonPrimitive(9),
            "head_pat_active_emotion_custom" to JsonPrimitive("missing"))) assertFalse(f.editor.preview(key, value))
        assertFalse(f.editor.preview("Unknown", JsonPrimitive(0), parameter = true))
        assertFalse(f.editor.preview("ParamMouthOpenY", JsonPrimitive(0), parameter = true))
        assertTrue(f.editor.preview("ParamAccent", JsonPrimitive(0.7), parameter = true))
        f.editor.submit()
        assertEquals(setOf("ParamAccent"), f.sent.single().parameters.keys)
    }

    @Test fun parameterResetUsesNullAndCloseDuringSaveStillTracksResult() {
        val f = fixture(); f.editor.preview("ParamAccent", JsonNull, parameter = true); f.editor.submit()
        assertEquals(JsonNull, f.sent.single().parameters["ParamAccent"])
        f.editor.close(); f.editor.receive(answer(f.sent.single())); f.editor.install(snapshot(2))
        assertFalse(f.editor.view.open); assertFalse(f.editor.view.busy)
        f.editor.open(); assertTrue(f.editor.view.open)
    }

    @Test fun visibleFieldsMatchTheTwentyAllowedSharedKeysAndSliderTypes() {
        assertEquals(20, characterSettingFields.size)
        assertEquals(20, characterSettingFields.map { it.key }.toSet().size)
        for (field in characterSettingFields) {
            for (value in field.samples()) ExtensionCodec.normalizeSettings(buildJsonObject { put(field.key, value) })
        }
        val field = characterSettingFields.single { it.key == "head_pat_fade_in_ms" }
        assertEquals(525L, characterSliderValue(field, 0.5f).long)
    }
}
