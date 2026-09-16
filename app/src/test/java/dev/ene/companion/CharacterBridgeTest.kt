package dev.ene.companion

import dev.ene.companion.character.CharacterBridge
import dev.ene.companion.character.CharacterRequestPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class CharacterBridgeTest {
    private val generation = "00000000-0000-4000-8000-000000000001"
    private val origin = "https://appassets.androidplatform.net"
    private val model = "a".repeat(64)
    private fun message(type: String, extra: String = "") = "{\"type\":\"$type\",\"generation\":\"$generation\"$extra}"

    @Test fun bridgeRequiresCurrentMainFrameOriginGenerationAndKnownType() {
        val bridge = CharacterBridge(generation)
        val ready = message("document_ready")
        assertNull(bridge.receive("https://example.invalid", true, ready))
        assertNull(bridge.receive(origin, false, ready))
        assertNull(bridge.receive(origin, true, ready.replace(generation, "00000000-0000-4000-8000-000000000002")))
        assertNull(bridge.receive(origin, true, message("open_url")))
        assertNull(bridge.receive(origin, true, "x".repeat(2049)))
        assertEquals("document_ready", bridge.receive(origin, true, ready)?.type)
        assertNull(bridge.receive(origin, true, ready))
        bridge.close()
        assertNull(bridge.receive(origin, true, message("error", ",\"code\":\"character_render_failed\"")))
    }

    @Test fun staleModelReadinessAndArbitraryErrorContentCannotReachNativeState() {
        val bridge = CharacterBridge(generation)
        bridge.expectModel(model)
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\"")))
        bridge.receive(origin, true, message("document_ready"))
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"${"b".repeat(64)}\"")))
        assertNull(bridge.receive(origin, true, message("error", ",\"code\":\"untrusted detail\"")))
        assertEquals("ready", bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\""))?.type)
    }

    @Test fun onlyFixedBundledFilesAndCurrentManifestAssetsResolve() {
        val policy = CharacterRequestPolicy(model, mapOf("b".repeat(64) to "application/json"))
        assertNotNull(policy.resolve("$origin/character/index.html", "GET", true))
        assertNotNull(policy.resolve("$origin/character/entry.js", "GET", false))
        assertNotNull(policy.resolve("$origin/models/$model/assets/${"b".repeat(64)}", "GET", false))
        for (url in listOf("https://example.invalid/character/entry.js", "$origin/character/../entry.js",
            "$origin/character/%65ntry.js", "$origin/character/entry.js?token=synthetic", "$origin/character/entry.js#part",
            "$origin:443/character/entry.js", "$origin/character/import-manifest.json", "$origin/character/missing.js",
            "file:///character/entry.js", "content://character/entry.js", "$origin/models/${"c".repeat(64)}/assets/${"b".repeat(64)}")) {
            assertNull(policy.resolve(url, "GET", false))
        }
        assertNull(policy.resolve("$origin/character/entry.js", "POST", false))
        assertNull(policy.resolve("$origin/character/entry.js", "GET", true))
        assertNull(policy.resolve("$origin/character/index.html", "GET", false))
    }

    @Test fun patInputRequiresCurrentModelAndTypedBoundedFields() {
        val bridge = CharacterBridge(generation)
        bridge.expectModel(model)
        bridge.receive(origin, true, message("document_ready"))
        val input = message("head_pat_input", ",\"model_generation\":\"$model\",\"interaction_id\":\"$generation\",\"seq\":0,\"phase\":\"start\",\"intensity\":0.5")
        assertEquals("start", bridge.receive(origin, true, input)?.input?.phase)
        assertNull(bridge.receive(origin, true, input.replace("0.5", "\"0.5\"")))
        assertNull(bridge.receive(origin, true, input.replace("0.5", "2.0")))
        assertNull(bridge.receive(origin, true, input.replace(model, "b".repeat(64))))
        assertNull(bridge.receive(origin, true, input.replace("\"seq\":0", "\"seq\":true")))
        assertNull(bridge.receive(origin, true, input.replace("\"start\"", "\"increment\"")))
    }

    @Test fun manifestLimitLeavesRoomForNativeEnvelope() {
        val bridge = CharacterBridge(generation)
        bridge.receive(origin, true, message("document_ready"))
        val value = buildJsonObject { put("synthetic", "a".repeat(262_128)) }
        assertEquals(262_144, value.toString().toByteArray().size)
        assertTrue(bridge.command("snapshot", value).length > 262_144)
    }
}
