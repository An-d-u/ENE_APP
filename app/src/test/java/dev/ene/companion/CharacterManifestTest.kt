package dev.ene.companion

import dev.ene.companion.character.CharacterSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** 실제 모델이 아닌 합성 목록으로 전달 계약과 자산 무결성 경계만 검사한다. */
internal object CharacterFixtures {
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun manifest(payload: ByteArray = "{}".toByteArray(), revision: Long = 1): String {
        val digest = hash(payload)
        val assets = buildJsonArray { add(buildJsonObject {
            put("id", digest); put("mime", "application/json"); put("sha256", digest); put("size", payload.size)
        }) }
        val version = hash(buildJsonObject {
            put("assets", assets); put("entry_asset_id", digest); put("runtime_version", 1)
        }.toString().toByteArray())
        return buildJsonObject {
            put("model_id", digest); put("model_version", version); put("runtime_version", 1)
            put("state_revision", revision); put("settings_revision", 1); put("action_seq", 3)
            put("settings", buildJsonObject { put("enable_head_pat", true) })
            put("parameters", buildJsonObject { put("ParamAccent", 0.25) })
            put("parameter_catalog", buildJsonArray { add(buildJsonObject {
                put("id", "ParamAccent"); put("min", -1); put("max", 1); put("default", 0)
            }) })
            put("expression_ids", buildJsonArray { add("normal"); add("bright") })
            put("gesture_ids", buildJsonArray { add("nod") })
            put("default_expression", "normal"); put("assets", assets)
            put("entry_asset_id", digest); put("status", "ready")
        }.toString()
    }

    fun changed(raw: String, key: String, value: JsonElement): String =
        JsonObject(Json.parseToJsonElement(raw).jsonObject + (key to value)).toString()
}

class CharacterManifestTest {
    private fun rejected(raw: String) {
        assertThrows(IllegalArgumentException::class.java) { CharacterSnapshot.parse(raw) }
    }

    @Test fun validSnapshotUsesContentVersionAndDiscardsUnknownRootFields() {
        val raw = CharacterFixtures.changed(CharacterFixtures.manifest(), "synthetic_private_field", JsonPrimitive("exclude"))
        val snapshot = CharacterSnapshot.parse(raw)
        assertEquals("ready", snapshot.status)
        assertEquals(1, snapshot.assets.size)
        assertEquals(3L, snapshot.actionSeq)
        assertEquals(0.25, snapshot.parameters.getValue("ParamAccent"), 0.0)
        assertFalse(snapshot.json.containsKey("synthetic_private_field"))
    }

    @Test fun changedAssetDescriptorCannotKeepAnOldVersion() {
        val raw = CharacterFixtures.manifest()
        val assets = Json.parseToJsonElement(raw).jsonObject.getValue("assets").jsonArray
        val changed = JsonArray(assets.map { JsonObject(it.jsonObject + ("size" to JsonPrimitive(999))) })
        rejected(CharacterFixtures.changed(raw, "assets", changed))
        rejected(CharacterFixtures.changed(raw, "model_version", JsonPrimitive("a".repeat(64))))
    }

    @Test fun unknownParametersBoundsAndDuplicateAssetIdsAreRejected() {
        val raw = CharacterFixtures.manifest()
        rejected(CharacterFixtures.changed(raw, "parameters", buildJsonObject { put("Missing", 0.0) }))
        rejected(CharacterFixtures.changed(raw, "parameters", buildJsonObject { put("ParamAccent", 2.0) }))
        val asset = Json.parseToJsonElement(raw).jsonObject.getValue("assets").jsonArray[0]
        rejected(CharacterFixtures.changed(raw, "assets", JsonArray(listOf(asset, asset))))
        rejected(CharacterFixtures.changed(raw, "runtime_version", JsonPrimitive(999)))
    }

    @Test fun traversalAndExcessiveBodiesAreRejected() {
        val raw = CharacterFixtures.manifest()
        rejected(CharacterFixtures.changed(raw, "entry_asset_id", JsonPrimitive("../escape")))
        rejected(" ".repeat(262145) + raw)
        rejected(CharacterFixtures.changed(raw, "settings", buildJsonObject { put("synthetic_unknown", true) }))
    }

    @Test fun unavailableSnapshotCannotRetainAReadyModelsAssets() {
        val raw = CharacterFixtures.manifest()
        rejected(CharacterFixtures.changed(raw, "status", JsonPrimitive("unsupported")))
        val source = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        source["status"] = JsonPrimitive("unsupported")
        listOf("model_id", "model_version", "entry_asset_id").forEach { source[it] = JsonNull }
        listOf("assets", "parameter_catalog", "expression_ids", "gesture_ids").forEach { source[it] = JsonArray(emptyList()) }
        source["parameters"] = JsonObject(emptyMap())
        val snapshot = CharacterSnapshot.parse(JsonObject(source).toString())
        assertNull(snapshot.modelVersion)
        assertTrue(snapshot.assets.isEmpty())
    }
}
