package dev.ene.companion.input

import org.junit.Assert.*
import org.junit.Test

class ImagePolicyTest {
    @Test fun originalBoundsAreValidatedBeforeDecode() {
        assertEquals(1, ImagePolicy.sampleSize(2048, 1000))
        assertEquals(16, ImagePolicy.sampleSize(32768, 1000))
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.sampleSize(32769, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.sampleSize(10001, 10000) }
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.sampleSize(0, 10) }
    }
    @Test fun originalReadStopsBeforeUnboundedAllocation() {
        val input = object : java.io.InputStream() {
            var remaining = 40 * 1024 * 1024
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(len, remaining); remaining -= count; return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.readBounded(input) }
        assertTrue(input.remaining > 0)
    }
    @Test fun animationFlagsAreRejectedWithoutDecoding() {
        val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0,0,0,8,97,99,84,76) + ByteArray(12)
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.rejectAnimation(png, "image/png") }
        val webp = "RIFF".toByteArray() + byteArrayOf(16,0,0,0) + "WEBPVP8X".toByteArray() + byteArrayOf(4,0,0,0,2,0,0,0)
        assertThrows(IllegalArgumentException::class.java) { ImagePolicy.rejectAnimation(webp, "image/webp") }
        ImagePolicy.rejectAnimation(byteArrayOf(1,2,3), "image/jpeg")
    }
}
