package dev.ene.companion

import dev.ene.companion.character.CharacterRequestPolicy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** 독립 앱 저장소에서도 PC 공유 실행부의 파일 목록·해시·고지를 검사한다. */
class CharacterRuntimeTest {
    @Test fun importedRuntimeExactlyMatchesManifestAndServingPolicy() {
        val root = File("src/main/assets/character")
        val manifest = Json.parseToJsonElement(File(root, "import-manifest.json").readText()).jsonObject
        assertEquals(1, manifest.getValue("runtime_version").jsonPrimitive.int)
        val files = manifest.getValue("files").jsonArray
        assertEquals(20, files.size)
        val targets = mutableSetOf<String>()
        val policy = CharacterRequestPolicy(null, emptyMap())
        for (element in files) {
            val entry = element.jsonObject
            val target = entry.getValue("target").jsonPrimitive.content
            assertTrue(targets.add(target))
            assertFalse(target.contains("..") || target.startsWith("/") || target.contains('\\'))
            val bytes = File(root, target).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(entry.getValue("sha256").jsonPrimitive.content, hash)
            val request = policy.resolve("${CharacterRequestPolicy.ORIGIN}/character/$target", "GET", target == "index.html")
            if (target.startsWith("notices/")) assertNull(request) else assertNotNull(request)
        }
        assertEquals(targets + "import-manifest.json", root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet())
        for (library in manifest.getValue("libraries").jsonArray) {
            for (notice in library.jsonObject.getValue("notices").jsonArray) assertTrue(notice.jsonPrimitive.content in targets)
        }
    }

    @Test fun nativeHostCannotFallBackToUnsafeBridgeOrNavigation() {
        val source = File("src/main/java/dev/ene/companion/character/CharacterWebView.kt").readText()
        assertFalse(source.contains("addJavascriptInterface("))
        assertFalse(source.contains("evaluateJavascript("))
        assertFalse(source.contains("intent:"))
        assertTrue(source.contains("setOf(CharacterRequestPolicy.ORIGIN)"))
        assertTrue(source.contains("handler.removeCallbacks(readinessTimeout)"))
    }
}
