package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** PC와 동일한 합성 입력으로 엄격한 확장 계약과 실행 권한을 확인한다. */
class ExtensionCodecTest {
    private val cases = Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResourceAsStream("media_cases.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject.getValue("cases").jsonArray
    private fun sample(kind: String): JsonObject = cases.first {
        it.jsonObject.getValue("name").jsonPrimitive.content == kind + "_정상"
    }.jsonObject.getValue("message").jsonObject

    @Test fun sharedContractCases() {
        for (case in cases) {
            val fields = case.jsonObject
            val error = fields["error"]?.jsonPrimitive?.content
            if (error != null) {
                val failure = assertThrows(fields.getValue("name").toString(), ProtocolException::class.java) {
                    ProtocolCodec.decode(fields.getValue("message").toString())
                }
                assertEquals(error, failure.code)
                assertEquals(error, failure.message)
            } else {
                val actual = ProtocolCodec.encode(ProtocolCodec.decode(fields.getValue("message").toString()))
                assertEquals(fields.getValue("name").toString(), fields.getValue("expected"), Json.parseToJsonElement(actual))
            }
        }
    }

    @Test fun capabilitiesAreDisabledByDefaultAndControlsNeedCharacter() {
        assertTrue(ExtensionCodec.negotiate(setOf("audio_pcm_v1")).isEmpty())
        assertTrue(ExtensionCodec.negotiate(setOf("character_controls_v1"), setOf("character_controls_v1", "character_v1")).isEmpty())
        assertEquals(setOf("audio_pcm_v1"), ExtensionCodec.negotiate(setOf("audio_pcm_v1", "unknown"), setOf("audio_pcm_v1")))
    }

    @Test fun guardRejectsStaleUnnegotiatedAndWrongDirection() {
        val body = sample("audio_prepared")
        val message = ProtocolCodec.decode(body.toString()) as ExtensionMessage
        val context = ExtensionContext(1, body.getValue("server_epoch").jsonPrimitive.content,
            body.getValue("connection_generation").jsonPrimitive.content, body.getValue("conversation_id").jsonPrimitive.content)
        ExtensionCodec.validate(message, context, setOf("audio_pcm_v1"), "from_phone", 24000)
        for (stale in listOf(context.copy(registrationGeneration = 2),
            context.copy(connectionGeneration = "00000000-0000-4000-8000-000000000099"),
            context.copy(serverEpoch = "00000000-0000-4000-8000-000000000099"),
            context.copy(conversationId = "00000000-0000-4000-8000-000000000099"))) {
            assertEquals("stale_extension", assertThrows(ProtocolException::class.java) {
                ExtensionCodec.validate(message, stale, setOf("audio_pcm_v1"), "from_phone")
            }.code)
        }
        assertEquals("extension_not_negotiated", assertThrows(ProtocolException::class.java) {
            ExtensionCodec.validate(message, context, emptySet(), "from_phone")
        }.code)
        assertEquals("unsupported_command", assertThrows(ProtocolException::class.java) {
            ExtensionCodec.validate(message, context, setOf("audio_pcm_v1"), "from_pc")
        }.code)
    }

    @Test fun rawControlLimitsAndFiniteNumbers() {
        for ((kind, size) in listOf("audio_progress" to 2048, "head_pat" to 2048, "character_settings_patch" to 49152)) {
            val raw = JsonObject(sample(kind) + ("ignored" to JsonPrimitive("x".repeat(size)))).toString()
            assertEquals("message_too_large", assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(raw) }.code)
        }
        for (value in listOf("NaN", "Infinity", "-Infinity", "1e999", "1" + "0".repeat(400))) {
            val raw = sample("audio_progress").toString().replace("\"mouth_open\":0.4", "\"mouth_open\":$value")
            val failure = assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(raw) }
            assertEquals(failure.code, failure.message)
        }
    }
}
