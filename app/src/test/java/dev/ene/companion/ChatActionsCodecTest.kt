package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 양쪽이 동일한 합성 계약을 사용하며 본문은 진단 문자열에 남기지 않는다. */
class ChatActionsCodecTest {
    private val cases = Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResourceAsStream("chat_actions_cases.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject.getValue("cases").jsonArray

    @Test fun sharedChatActionContract() {
        for (case in cases) {
            val fields = case.jsonObject
            val raw = fields.getValue("message").toString()
            val error = fields["error"]?.jsonPrimitive?.content
            if (error == null) assertEquals(fields.getValue("expected"), Json.parseToJsonElement(ProtocolCodec.encode(ProtocolCodec.decode(raw))))
            else assertEquals(fields.getValue("name").toString(), error, assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(raw) }.code)
        }
    }

    @Test fun chatActionsNeedNoMediaCapability() {
        assertEquals(setOf("chat_actions_v1"), ExtensionCodec.negotiate(setOf("chat_actions_v1"), setOf("chat_actions_v1")))
        assertTrue(ExtensionCodec.negotiate(setOf("chat_actions_v1"), setOf("audio_pcm_v1")).isEmpty())
    }

    @Test fun textLimitAndPrivacy() {
        val sample = cases[0].jsonObject.getValue("message").jsonObject
        fun message(text: String) = JsonObject(sample + ("text" to JsonPrimitive(text))).toString()
        ProtocolCodec.decode(message("가".repeat(5461) + "a"))
        assertEquals("text_too_large", assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(message("가".repeat(5461) + "ab")) }.code)
        val decoded = ProtocolCodec.decode(sample.toString())
        assertFalse(decoded.toString().contains(sample.getValue("text").jsonPrimitive.content))
        val context = ExtensionContext(1, sample.getValue("server_epoch").jsonPrimitive.content,
            sample.getValue("connection_generation").jsonPrimitive.content, sample.getValue("conversation_id").jsonPrimitive.content)
        ExtensionCodec.validate(decoded as ExtensionMessage, context, setOf("chat_actions_v1"), "from_phone")
        assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(decoded, context, emptySet(), "from_phone") }
        assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(decoded, context, setOf("chat_actions_v1"), "from_pc") }
    }
}
