package dev.ene.companion

import dev.ene.companion.character.*
import org.junit.Assert.*
import org.junit.Test

class CharacterPlacementTest {
    @Test fun validatesFiniteRangesAndRoundTripsOnlyPlacementFields() {
        for (value in listOf(CharacterPlacement(), CharacterPlacement(.5, 0.0, 100.0), CharacterPlacement(2.0, 100.0, 0.0))) {
            assertEquals(value, CharacterPlacementCodec.decode(CharacterPlacementCodec.encode(value)))
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, .49, 2.01)) {
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(scale = value) }
        }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(xPercent = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(yPercent = 101.0) }
    }

    @Test fun rejectsCorruptOrForeignStorageWithoutCoercion() {
        for (raw in listOf("{}", "[]", "broken", """{"version":2,"scale":1,"xPercent":50,"yPercent":50}""",
            """{"version":1,"scale":"1","xPercent":50,"yPercent":50}""",
            """{"version":1,"scale":1,"xPercent":-1,"yPercent":50}""",
            """{"version":1,"scale":1,"xPercent":50,"yPercent":50,"extra":true}""")) {
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(raw.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(ByteArray(8193)) }
    }
}
