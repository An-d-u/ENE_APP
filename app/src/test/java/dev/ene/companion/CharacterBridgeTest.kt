package dev.ene.companion

import dev.ene.companion.character.CharacterBridge
import dev.ene.companion.character.CharacterRequestPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class CharacterBridgeTest {
    @Test fun obsoletePresentationCannotReportReadyOrFailureInTheSameDocument() {
        val bridge = CharacterBridge(generation)
        bridge.expectModel(model)
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        bridge.receive(origin, true, message("document_ready"))
        // 현재 세대가 없는 모델 사건부터 거절해야 한다.
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\",\"presentation_generation\":99")))
        bridge.bindPresentation(1)
        assertNotNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\"")))
        bridge.bindPresentation(2)
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\"")))
        assertNull(bridge.receive(origin, true, message("error", ",\"code\":\"character_render_failed\"")))
        assertEquals("document_error", bridge.receive(origin, true, message("document_error", ",\"code\":\"character_render_failed\""))?.type)
        for (value in listOf(0L, -1L, 2L, 9_007_199_254_740_992L)) {
            assertThrows(IllegalArgumentException::class.java) { bridge.bindPresentation(value) }
        }
    }
    @Test fun presentationAcceptsExpandedBoundsButRejectsOutOfRangeNumbers() {
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        bridge.receive(origin, true, message("document_ready"))
        for ((x, y) in listOf(-300 to 400, 400 to -300)) {
            val value = Json.parseToJsonElement("""{"visible":true,"placement":{"scale":6,"xPercent":$x,"yPercent":$y}}""").jsonObject
            assertEquals(value, Json.parseToJsonElement(bridge.command("presentation", value)).jsonObject["value"])
            val placement = value.getValue("placement").jsonObject
            for ((key, number) in listOf("scale" to .49, "scale" to 6.01, "xPercent" to -300.01,
                "xPercent" to 400.01, "yPercent" to -300.01, "yPercent" to 400.01)) {
                val bad = JsonObject(value + ("placement" to JsonObject(placement + (key to JsonPrimitive(number)))))
                assertThrows(IllegalArgumentException::class.java) { bridge.command("presentation", bad) }
            }
        }
    }

    @Test fun presentationIsStrictAndUnavailableBeforeDocumentReadyOrAfterClose() {
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        val value = dev.ene.companion.character.CharacterPlacement(1.5, 25.0, 75.0).presentation(false)
        assertThrows(IllegalStateException::class.java) { bridge.command("presentation", value) }
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        bridge.receive(origin, true, message("document_ready"))
        assertEquals(value, Json.parseToJsonElement(bridge.command("presentation", value)).jsonObject["value"])
        for (bad in listOf(JsonObject(value + ("extra" to JsonPrimitive(1))),
            JsonObject(value + ("visible" to JsonPrimitive("false"))),
            JsonObject(value + ("placement" to JsonObject(emptyMap()))))) {
            assertThrows(IllegalArgumentException::class.java) { bridge.command("presentation", bad) }
        }
        bridge.close()
        assertThrows(IllegalStateException::class.java) { bridge.command("presentation", value) }
    }
    private val generation = "00000000-0000-4000-8000-000000000001"
    private val origin = "https://appassets.androidplatform.net"
    private val model = "a".repeat(64)
    private fun message(type: String, extra: String = "") = "{\"type\":\"$type\",\"generation\":\"$generation\",\"presentation_generation\":1$extra}"

    @Test fun replyChannelStartsOnlyOnceFromAllowedMainFrame() {
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        val hello = "{\"type\":\"bridge_ready\"}"
        assertNull(bridge.receive(origin, true, message("document_ready")))
        assertNull(bridge.receive("https://example.invalid", true, hello))
        assertNull(bridge.receive(origin, false, hello))
        assertEquals("bridge_ready", bridge.receive(origin, true, hello)?.type)
        assertNull(bridge.receive(origin, true, hello))
        assertEquals("document_ready", bridge.receive(origin, true, message("document_ready"))?.type)
        bridge.close()
        assertNull(bridge.receive(origin, true, hello))
    }

    @Test fun initializationFailureCanArriveBeforeDocumentReadyWithoutArbitraryDetails() {
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        assertNull(bridge.receive(origin, true, message("error", ",\"code\":\"synthetic secret\"")))
        assertEquals("character_initialization_failed", bridge.receive(origin, true,
            message("error", ",\"code\":\"character_initialization_failed\""))?.code)
    }

    @Test fun bridgeRequiresCurrentMainFrameOriginGenerationAndKnownType() {
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        val ready = message("document_ready")
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
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
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        bridge.expectModel(model)
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"$model\"")))
        bridge.receive(origin, true, message("document_ready"))
        assertNull(bridge.receive(origin, true, message("ready", ",\"model_version\":\"${"b".repeat(64)}\"")))
        assertNull(bridge.receive(origin, true, message("error", ",\"code\":\"untrusted detail\"")))
        for (code in listOf("character_asset_failed", "character_render_failed")) {
            assertEquals(code, bridge.receive(origin, true, message("error", ",\"code\":\"$code\""))?.code)
        }
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
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        bridge.expectModel(model)
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
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
        val bridge = CharacterBridge(generation).also { it.bindPresentation(1) }
        bridge.receive(origin, true, "{\"type\":\"bridge_ready\"}")
        bridge.receive(origin, true, message("document_ready"))
        val value = buildJsonObject { put("synthetic", "a".repeat(262_128)) }
        assertEquals(262_144, value.toString().toByteArray().size)
        assertTrue(bridge.command("snapshot", value).length > 262_144)
    }
}
