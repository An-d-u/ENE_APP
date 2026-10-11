package dev.ene.companion.input

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 디코더 호출 전 원본 읽기·픽셀 수·애니메이션을 제한한다. */
object ImagePolicy {
    const val ORIGINAL_BYTES = 32 * 1024 * 1024
    const val IMAGE_BYTES = 4 * 1024 * 1024
    fun sampleSize(width: Int, height: Int): Int {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 100_000_000) { "image_dimensions" }
        var sample = 1
        while (maxOf(width, height) / sample > 2048) sample *= 2
        return sample
    }
    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val scratch = ByteArray(32768)
        while (true) {
            val count = input.read(scratch, 0, minOf(scratch.size, ORIGINAL_BYTES - output.size() + 1))
            if (count < 0) break
            require(output.size().toLong() + count <= ORIGINAL_BYTES) { "image_too_large" }
            output.write(scratch, 0, count)
        }
        require(output.size() > 0) { "invalid_image" }
        return output.toByteArray()
    }
    fun rejectAnimation(bytes: ByteArray, mime: String) {
        require(mime in setOf("image/jpeg", "image/png", "image/webp")) { "unsupported_image" }
        if (mime == "image/jpeg") return
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun number(offset: Int, little: Boolean): Long {
            var value = 0L
            for (index in 0..3) value = (value shl 8) or (bytes[offset + if (little) 3 - index else index].toLong() and 255)
            return value
        }
        var offset = if (mime == "image/png") 8 else 12
        while (offset + 8 <= bytes.size) {
            val png = mime == "image/png"
            val size = number(offset + if (png) 0 else 4, !png)
            val name = tag(offset + if (png) 4 else 0)
            require(name !in setOf("acTL", "ANIM", "ANMF")) { "animated_image" }
            if (!png && name == "VP8X" && size > 0 && offset + 8 < bytes.size)
                require(bytes[offset + 8].toInt() and 2 == 0) { "animated_image" }
            val next = offset.toLong() + 8 + size + if (png) 4 else size % 2
            require(next <= bytes.size && next > offset) { "invalid_image" }
            offset = next.toInt()
        }
        require(offset == bytes.size) { "invalid_image" }
    }
}
