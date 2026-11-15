package dev.ene.companion.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CameraWireContractTest {
    @Test fun sharesCameraCasesWithPcAndRejectsOtherSources() {
        checkCases("camera")
        checkCases("observation_intervals")
    }

    private fun checkCases(feature: String) {
        val data = requireNotNull(javaClass.getResource("/${feature}_cases.json")).readText()
        val negotiated = setOf("${feature}_v1")
        for (item in Json.parseToJsonElement(data).jsonArray.map { it.jsonObject }) {
            val body = item.getValue("body").jsonObject
            if (!item.getValue("valid").jsonPrimitive.boolean) {
                assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(body.toString()) }
                continue
            }
            val frame = ProtocolCodec.decode(body.toString()) as ExtensionMessage
            assertEquals(item.getValue("name").toString(), body, Json.parseToJsonElement(ProtocolCodec.encode(frame)))
            val scope = ExtensionContext(1, body.getValue("server_epoch").jsonPrimitive.content,
                body.getValue("connection_generation").jsonPrimitive.content, body.getValue("conversation_id").jsonPrimitive.content)
            val pc = body.getValue("type").jsonPrimitive.content.endsWith("_state")
            val source = if (pc) "from_pc" else "from_phone"
            ExtensionCodec.validate(frame, scope, negotiated, source)
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(frame, scope, setOf("chat_images_v1"), source) }
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(frame, scope.copy(registrationGeneration = 2), negotiated, source) }
            assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(frame, scope, negotiated, if (pc) "from_phone" else "from_pc") }
        }
    }
}
