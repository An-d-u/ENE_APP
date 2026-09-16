package dev.ene.companion

import dev.ene.companion.character.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 정적 파일 정책과 실제 입력 파서를 함께 검사하되 실기기 보안 검증으로 표현하지 않는다. */
class MediaPrivacyPolicyTest {
    @Test fun bundledCharacterContainsNoPcChatRuntimeOrPrivateModelFiles() {
        val root = File("src/main/assets/character")
        val manifest = Json.parseToJsonElement(File(root, "import-manifest.json").readText()).jsonObject
        assertTrue(manifest.isNotEmpty())
        val names = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        assertEquals(21, names.size)
        for (name in names) assertFalse(name.endsWith(".model3.json") || name.endsWith(".moc3") ||
            name in setOf("script.js", "runtime_bridge.js", "runtime_settings.js", "config.json", "api_keys.json", "ca.der"))
        val view = File("src/main/java/dev/ene/companion/character/CharacterWebView.kt").readText()
        for (forbidden in listOf("addJavascriptInterface(", "evaluateJavascript(", "proceed()")) assertFalse(view.contains(forbidden))
        val settings = File("src/main/java/dev/ene/companion/ui/CharacterSettingsSheet.kt").readText()
        for (forbidden in listOf("rememberSaveable", "SharedPreferences", "Log.", "println(")) assertFalse(settings.contains(forbidden))
    }

    @Test fun closedOrForeignDocumentCannotSendControlCommandsAcrossTwentyReplacements() {
        val model = "a".repeat(64)
        repeat(20) { index ->
            val bridge = CharacterBridge(audioId(100 + index)); bridge.expectModel(model)
            val ready = buildJsonObject { put("type", "document_ready"); put("generation", bridge.generation) }.toString()
            assertNull(bridge.receive("https://example.invalid", true, ready))
            assertNotNull(bridge.receive(CharacterRequestPolicy.ORIGIN, true, ready))
            for (kind in listOf("save_settings", "open_url", "read_file", "increment_head_pat_count")) {
                val input = buildJsonObject { put("type", kind); put("generation", bridge.generation) }.toString()
                assertNull(bridge.receive(CharacterRequestPolicy.ORIGIN, true, input))
            }
            bridge.close()
            assertNull(bridge.receive(CharacterRequestPolicy.ORIGIN, true, ready))
            assertThrows(IllegalStateException::class.java) { bridge.command("preview", JsonObject(emptyMap())) }
        }
    }
}
