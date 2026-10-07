package dev.ene.companion

import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ThoughtCodecTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val context = ExtensionContext(1, id(1), id(2), id(3))
    private val response = ThoughtResponse(1, id(1), id(2), id(3), 2, id(4), id(5), "available", "가상 구름을 관찰한다.")

    @Test fun responseRoundTripAndNegotiatedDirection() {
        assertEquals(response, ProtocolCodec.decode(ProtocolCodec.encode(response)))
        ExtensionCodec.validate(response, context, setOf("message_thoughts_v1"), "from_pc")
        assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(response, context, emptySet(), "from_pc") }
        assertThrows(ProtocolException::class.java) { ExtensionCodec.validate(response, context, setOf("message_thoughts_v1"), "from_phone") }
        assertFalse(response.toString().contains(response.text))
    }

    @Test fun sizeStatusAndWhitespaceAreStrict() {
        ProtocolCodec.encode(response.copy(text = "\u0001".repeat(8192)))
        for (value in listOf(response.copy(text = "나".repeat(2731)), response.copy(text = " "),
            response.copy(status = "empty"), response.copy(status = "unexpected", text = ""))) {
            assertThrows(ProtocolException::class.java) { ProtocolCodec.encode(value) }
        }
    }
}
