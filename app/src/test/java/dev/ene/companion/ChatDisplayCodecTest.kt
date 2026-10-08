package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatDisplayCodecTest {
    private val cases = Json.parseToJsonElement(javaClass.getResource("/chat_display_cases.json")!!.readText()).jsonArray
    private val caps = setOf("chat_display_v1")

    @Test fun sharedCasesAndConnectionBoundary() {
        for (case in cases.map { it.jsonObject }) {
            val body = case.getValue("body").jsonObject
            if (!case.getValue("valid").jsonPrimitive.boolean) {
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(body.toString()) }
                continue
            }
            val message = ProtocolCodec.decode(body.toString()) as ExtensionMessage
            assertEquals(body, Json.parseToJsonElement(ProtocolCodec.encode(message)))
            val context = ExtensionContext(1, message.server_epoch, message.connection_generation, message.server_epoch)
            val direction = if (body.getValue("type").jsonPrimitive.content == "chat_display_request") "from_phone" else "from_pc"
            ExtensionCodec.validate(message, context, caps, direction)
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, context, emptySet(), direction) }
            assertThrows(ProtocolException::class.java) {
                ExtensionCodec.validate(message, context, caps, if (direction == "from_pc") "from_phone" else "from_pc")
            }
            for (stale in listOf(context.copy(registrationGeneration = 2),
                context.copy(serverEpoch = message.connection_generation), context.copy(connectionGeneration = message.server_epoch))) {
                assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, stale, caps, direction) }
            }
            for (key in body.keys - setOf("type", "protocol_version")) {
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(JsonObject(body - key).toString()) }
            }
            assertThrows(ProtocolException::class.java) {
                ProtocolCodec.decode(JsonObject(body + ("ignored" to JsonPrimitive("x".repeat(2048)))).toString())
            }
        }
    }

    @Test fun displayNegotiatesWithoutMedia() {
        assertEquals(caps, ExtensionCodec.negotiate(caps, caps))
        assertEquals(emptySet<String>(), ExtensionCodec.negotiate(caps))
    }
}
