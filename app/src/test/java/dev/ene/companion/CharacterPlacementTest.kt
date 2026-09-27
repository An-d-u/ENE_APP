package dev.ene.companion

import dev.ene.companion.character.*
import org.junit.Assert.*
import org.junit.Test

class CharacterPlacementTest {
    @Test fun validatesFiniteRangesAndRoundTripsOnlyPlacementFields() {
        for (value in listOf(CharacterPlacement(), CharacterPlacement(.5, 0.0, 100.0), CharacterPlacement(2.0, 100.0, 0.0),
            CharacterPlacement(6.0, -300.0, 400.0), CharacterPlacement(6.0, 400.0, -300.0), CharacterPlacement(2.01, -1.0, 101.0))) {
            assertEquals(value, CharacterPlacementCodec.decode(CharacterPlacementCodec.encode(value)))
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, .49, 6.01)) {
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(scale = value) }
        }
        for (value in listOf(-300.01, 400.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(xPercent = value) }
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacement(yPercent = value) }
        }
    }

    @Test fun rejectsCorruptOrForeignStorageWithoutCoercion() {
        for (raw in listOf("{}", "[]", "broken", """{"version":2,"scale":1,"xPercent":50,"yPercent":50}""",
            """{"version":1,"scale":"1","xPercent":50,"yPercent":50}""",
            """{"version":1,"scale":1,"xPercent":-300.01,"yPercent":50}""",
            """{"version":1,"scale":1,"xPercent":50,"yPercent":400.01}""",
            """{"version":1,"scale":1,"xPercent":50,"yPercent":50,"extra":true}""")) {
            assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(raw.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacementCodec.decode(ByteArray(8193)) }
    }
}
