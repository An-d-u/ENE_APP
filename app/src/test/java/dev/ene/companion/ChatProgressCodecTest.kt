package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatProgressCodecTest {
    private val cases = Json.parseToJsonElement(javaClass.getResource("/chat_progress_cases.json")!!.readText()).jsonArray
    private val caps = setOf("chat_progress_v1")

    @Test fun sharedCasesAndCurrentScope() {
        for (case in cases.map { it.jsonObject }) {
            val body = case.getValue("body").jsonObject
            if (!case.getValue("valid").jsonPrimitive.boolean) {
                assertThrows(case.getValue("name").toString(), ProtocolException::class.java) { ProtocolCodec.decode(body.toString()) }
                continue
            }
            val message = ProtocolCodec.decode(body.toString()) as ExtensionMessage
            assertEquals(body, Json.parseToJsonElement(ProtocolCodec.encode(message)))
            val conversation = "00000000-0000-4000-8000-000000000003"
            val context = ExtensionContext(1, message.server_epoch, message.connection_generation, conversation)
            val direction = if (body.getValue("type").jsonPrimitive.content == "chat_progress_request") "from_phone" else "from_pc"
            ExtensionCodec.validate(message, context, caps, direction)
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, context, emptySet(), direction) }
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, context, caps, if (direction == "from_phone") "from_pc" else "from_phone") }
            val stale = listOf(context.copy(registrationGeneration = 2), context.copy(serverEpoch = conversation),
                context.copy(connectionGeneration = conversation)) + if ("conversation_id" in body) listOf(context.copy(conversationId = message.server_epoch)) else emptyList()
            for (value in stale) assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, value, caps, direction) }
            for (key in body.keys - setOf("type", "protocol_version")) {
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(JsonObject(body - key).toString()) }
            }
            if (body.getValue("type").jsonPrimitive.content != "extension_error") {
                val raw = body.toString()
                assertNotNull(ProtocolCodec.decode(raw + " ".repeat(2048 - raw.toByteArray(Charsets.UTF_8).size)))
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(raw + " ".repeat(2049 - raw.toByteArray(Charsets.UTF_8).size)) }
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(JsonObject(body + ("ignored" to JsonPrimitive("x".repeat(2048)))).toString()) }
            }
        }
    }

    @Test fun negotiatesWithoutMedia() {
        assertEquals(caps, ExtensionCodec.negotiate(caps, caps))
        assertEquals(emptySet<String>(), ExtensionCodec.negotiate(caps))
    }
}
