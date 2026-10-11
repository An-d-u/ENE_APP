package dev.ene.companion.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InputProtocolTest {
    @Test fun sharedCasesPreserveScopePurposeAndDirection() {
        val phone = setOf("input_reserve", "input_cancel", "chat_images_send", "chat_images_request",
            "chat_images_limits_request", "stt_limits_request", "stt_begin", "stt_transcribe", "stt_cancel")
        for (feature in listOf("chat_images", "stt_input")) {
            val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/${feature}_cases.json")).readText()).jsonArray
            for (case in cases) {
                val item = case.jsonObject
                val body = item.getValue("body").jsonObject
                if (!item.getValue("valid").jsonPrimitive.boolean) {
                    assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(body.toString()) }
                    continue
                }
                val message = ProtocolCodec.decode(body.toString()) as ExtensionMessage
                assertEquals(item.getValue("name").toString(), body, Json.parseToJsonElement(ProtocolCodec.encode(message)))
                val context = ExtensionContext(1, body.getValue("server_epoch").jsonPrimitive.content,
                    body.getValue("connection_generation").jsonPrimitive.content, body.getValue("conversation_id").jsonPrimitive.content)
                val direction = if (body.getValue("type").jsonPrimitive.content in phone) "from_phone" else "from_pc"
                ExtensionCodec.validate(message, context, setOf("${feature}_v1"), direction)
                assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(message, context, emptySet(), direction) }
                assertThrows(ProtocolException::class.java) {
                    ExtensionCodec.validate(message, context.copy(registrationGeneration = 2), setOf("${feature}_v1"), direction)
                }
                assertThrows(ProtocolException::class.java) {
                    ExtensionCodec.validate(message, context, setOf("${feature}_v1"), if (direction == "from_pc") "from_phone" else "from_pc")
                }
                for (key in body.keys - setOf("type", "protocol_version")) {
                    assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(JsonObject(body - key).toString()) }
                }
            }
        }
    }
}
