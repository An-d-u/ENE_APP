package dev.ene.companion.input

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class NormalizedImage(val bytes: ByteArray, val width: Int, val height: Int, val mime: String) {
    override fun toString() = "NormalizedImage($width,$height,$mime)"
}

/** 호출자는 IO 작업에서 한 장씩 실행한다. 원본 metadata는 픽셀 출력에 복사하지 않는다. */
object ImagePreparation {
    fun prepare(input: InputStream): NormalizedImage {
        val raw = ImagePolicy.readBounded(input)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val sample = ImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
        val mime = bounds.outMimeType ?: throw IllegalArgumentException("unsupported_image")
        ImagePolicy.rejectAnimation(raw, mime)
        val orientation = ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var pixels = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: throw IllegalArgumentException("invalid_image")
        try {
            val transform = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }
                    6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }
                    8 -> setRotate(270f)
                }
            }
            if (!transform.isIdentity) {
                val rotated = Bitmap.createBitmap(pixels, 0, 0, pixels.width, pixels.height, transform, true)
                if (rotated !== pixels) pixels.recycle()
                pixels = rotated
            }
            val png = pixels.hasAlpha()
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            while (true) {
                val output = LimitedImageOutput(ImagePolicy.IMAGE_BYTES)
                val encoded = try {
                    require(pixels.compress(format, 90, output)) { "image_encode_failed" }
                    output.toByteArray()
                } catch (_: ImageSizeLimit) { null }
                if (encoded != null) return NormalizedImage(encoded, pixels.width, pixels.height, if (png) "image/png" else "image/jpeg")
                require(maxOf(pixels.width, pixels.height) > 512) { "image_too_large" }
                val smaller = Bitmap.createScaledBitmap(pixels, maxOf(1, pixels.width * 3 / 4), maxOf(1, pixels.height * 3 / 4), true)
                if (smaller !== pixels) pixels.recycle()
                pixels = smaller
            }
        } finally { pixels.recycle() }
    }

    fun preview(image: NormalizedImage): ByteArray {
        val pixels = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
            ?: throw IllegalArgumentException("invalid_image")
        var scaled: Bitmap? = null
        try {
            val scale = minOf(1f, 320f / maxOf(pixels.width, pixels.height))
            scaled = Bitmap.createScaledBitmap(pixels, maxOf(1, (pixels.width * scale).toInt()), maxOf(1, (pixels.height * scale).toInt()), true)
            val output = ByteArrayOutputStream()
            // 로컬 미리보기이며 업로드 원본의 투명도는 그대로 보존한다.
            require(scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)) { "image_encode_failed" }
            require(output.size() <= 128 * 1024) { "image_too_large" }
            return output.toByteArray()
        } finally {
            if (scaled !== pixels) scaled?.recycle()
            pixels.recycle()
        }
    }
}

private class ImageSizeLimit : RuntimeException()
private class LimitedImageOutput(private val maximum: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) { if (count >= maximum) throw ImageSizeLimit(); super.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (count.toLong() + length > maximum) throw ImageSizeLimit()
        super.write(bytes, offset, length)
    }
}
