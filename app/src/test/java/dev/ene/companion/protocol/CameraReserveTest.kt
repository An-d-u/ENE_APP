package dev.ene.companion.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CameraReserveTest {
    private val id = "00000000-0000-4000-8000-000000000004"
    private fun reservation(generation: Long?, length: Long) = buildJsonObject {
        put("protocol_version",1); put("type","input_reserve"); put("registration_generation",1)
        for (key in listOf("server_epoch","connection_generation","conversation_id","command_id")) put(key,id)
        put("purpose","camera"); put("sha256","0".repeat(64)); put("byte_length",length)
        generation?.let { put("generation",it) }
    }
    @Test fun cameraUploadIsScopedToConsentAndNeverNegotiatedAsPhotoOrVoice() {
        val body = reservation(3, 1048576)
        val ref = ProtocolCodec.decode(body.toString()) as ExtensionMessage
        assertEquals(body, Json.parseToJsonElement(ProtocolCodec.encode(ref)))
        val c = ExtensionContext(1,id,id,id)
        ExtensionCodec.validate(ref,c,setOf("camera_v1"),"from_phone")
        assertThrows(ProtocolException::class.java) {
            ExtensionCodec.validate(ref,c,setOf("chat_images_v1","stt_input_v1"),"from_phone")
        }
        for (bad in listOf(reservation(null,5),reservation(0,5),reservation(1,1048577)))
            assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(bad.toString()) }
    }
}
