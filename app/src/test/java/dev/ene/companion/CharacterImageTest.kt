package dev.ene.companion

import dev.ene.companion.character.CharacterImage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class CharacterImageTest {
    private fun png(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33)
        .put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)).putInt(13)
        .put("IHDR".toByteArray()).putInt(width).putInt(height).put(byteArrayOf(8, 6, 0, 0, 0)).putInt(0).array()
    private fun jpeg(width: Int, height: Int) = ByteBuffer.allocate(19)
        .put(255.toByte()).put(216.toByte()).put(255.toByte()).put(192.toByte()).putShort(11).put(8)
        .putShort(height.toShort()).putShort(width.toShort()).put(1).put(byteArrayOf(1, 17, 0))
        .put(255.toByte()).put(217.toByte()).array()

    @Test fun boundedTextureHeadersAreAcceptedWithoutAllocatingPixels() {
        CharacterImage.verify(ByteArrayInputStream(png(8192, 1)), "image/png")
        CharacterImage.verify(ByteArrayInputStream(jpeg(32, 8192)), "image/jpeg")
    }

    @Test fun oversizeZeroAndTruncatedHeadersAreRejected() {
        for ((bytes, mime) in listOf(png(8193, 1) to "image/png", png(0, 2) to "image/png",
            jpeg(1, 8193) to "image/jpeg", png(10, 10).copyOf(17) to "image/png", byteArrayOf(0, 0) to "image/jpeg")) {
            assertThrows(IllegalArgumentException::class.java) { CharacterImage.verify(ByteArrayInputStream(bytes), mime) }
        }
    }
}
