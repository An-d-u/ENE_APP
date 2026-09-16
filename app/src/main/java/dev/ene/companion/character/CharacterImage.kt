package dev.ene.companion.character

import java.io.DataInputStream
import java.io.InputStream

/** PNG/JPEG 치수만 읽는다. 디코더가 거대한 픽셀 배열을 할당하기 전에 상한을 확인한다. */
object CharacterImage {
    fun verify(input: InputStream, mime: String) {
        if (mime !in setOf("image/png", "image/jpeg")) return
        try {
            val data = DataInputStream(input)
            if (mime == "image/png") {
                val signature = ByteArray(8).also { data.readFully(it) }
                require(signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
                require(data.readInt() == 13 && data.readInt() == 0x49484452)
                dimensions(data.readInt(), data.readInt())
                data.readFully(ByteArray(9))
            } else {
                require(data.readUnsignedShort() == 0xffd8)
                var read = 2L
                while (read < CharacterSnapshot.MAX_ASSET_BYTES) {
                    require(data.readUnsignedByte() == 255)
                    var marker = data.readUnsignedByte()
                    read += 2
                    while (marker == 255 && read < CharacterSnapshot.MAX_ASSET_BYTES) { marker = data.readUnsignedByte(); read++ }
                    require(marker !in setOf(0, 0xd8, 0xd9, 0xda) && marker !in 0xd0..0xd7)
                    val length = data.readUnsignedShort()
                    require(length >= 2)
                    read += length
                    require(read <= CharacterSnapshot.MAX_ASSET_BYTES)
                    if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                        require(length >= 8)
                        data.readUnsignedByte()
                        val height = data.readUnsignedShort()
                        val width = data.readUnsignedShort()
                        dimensions(width, height)
                        val components = data.readUnsignedByte()
                        require(components in 1..4 && length == 8 + 3 * components)
                        data.readFully(ByteArray(3 * components))
                        return
                    }
                    var remaining = length - 2
                    val scratch = ByteArray(minOf(8192, remaining))
                    while (remaining > 0) {
                        val count = minOf(scratch.size, remaining)
                        data.readFully(scratch, 0, count)
                        remaining -= count
                    }
                }
                throw CharacterException("invalid_texture")
            }
        } catch (_: java.io.IOException) { throw CharacterException("invalid_texture") }
        catch (_: IllegalArgumentException) { throw CharacterException("invalid_texture") }
    }

    private fun dimensions(width: Int, height: Int) {
        require(width in 1..8192 && height in 1..8192) { "invalid_texture" }
    }
}
