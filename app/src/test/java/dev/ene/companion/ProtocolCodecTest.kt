package dev.ene.companion

import dev.ene.companion.protocol.ProtocolCodec
import dev.ene.companion.protocol.ProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** PC와 같은 가상 계약 데이터를 실제 해석기로 검증한다. */
class ProtocolCodecTest {
    @Test
    fun sharedContractCases() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("cases.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases = Json.parseToJsonElement(fixture).jsonObject.getValue("cases").jsonArray
        for (case in cases) {
            val fields = case.jsonObject
            val raw = fields.getValue("message").toString()
            val error = fields["error"]?.jsonPrimitive?.content
            if (error != null) {
                val failure = assertThrows(fields.getValue("name").toString(), ProtocolException::class.java) {
                    ProtocolCodec.decode(raw)
                }
                assertEquals(error, failure.code)
            } else {
                val actual = Json.parseToJsonElement(ProtocolCodec.encode(ProtocolCodec.decode(raw)))
                assertEquals(fields.getValue("name").toString(), fields.getValue("expected"), actual)
            }
        }
    }

    @Test
    fun utf8ByteLimitAndDepthLimit() {
        val prefix = """{"type":"send_text","protocol_version":1,"server_epoch":"00000000-0000-4000-8000-000000000001","conversation_id":"00000000-0000-4000-8000-000000000002","request_id":"00000000-0000-4000-8000-000000000003","text":""""
        ProtocolCodec.decode(prefix + "🪐".repeat(4096) + "\"}")
        assertEquals("text_too_large", assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decode(prefix + "🪐".repeat(4097) + "\"}")
        }.code)
        assertEquals("message_too_large", assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decode(" ".repeat(65537))
        }.code)
        assertThrows(ProtocolException::class.java) {
            ProtocolCodec.decode("""{"type":"hello","protocol_version":1,"extra":${"[".repeat(20)}0${"]".repeat(20)}}""")
        }
    }

    @Test
    fun invalidUnicodeAndJsonAreSafeErrors() {
        for (raw in listOf("{", "[]", """{"type":"hello","protocol_version":NaN}""", """{"type":"hello","protocol_version":1,"capabilities":["\uD800"]}""")) {
            val failure = assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(raw) }
            assertEquals(failure.code, failure.message)
        }
    }

    @Test
    fun generatedCredentialsAreHiddenFromObjectDescription() {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val message = ProtocolCodec.decode("""{"type":"pair_request","protocol_version":1,"pairing_id":"00000000-0000-4000-8000-000000000001","secret":"$secret","device_name":"가상 단말"}""")
        assertFalse(message.toString().contains(secret))
    }
}
