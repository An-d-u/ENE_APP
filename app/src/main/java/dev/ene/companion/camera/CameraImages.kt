package dev.ene.companion.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** YUV 행 패딩과 픽셀 간격을 고려해 중앙 표본들을 읽는다. */
internal fun cameraSignature(image: ImageProxy): IntArray {
    val crop = image.cropRect
    val result = IntArray(24 * 14 * 3)
    for ((channel, plane) in image.planes.withIndex()) {
        val subsample = if (channel == 0) 1 else 2
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        for (row in 0 until 14) for (column in 0 until 24) {
            val x = (crop.left + (2 * column + 1) * crop.width() / 48) / subsample
            val y = (crop.top + (2 * row + 1) * crop.height() / 28) / subsample
            result[(row * 24 + column) * 3 + channel] =
                buffer.get(base + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
        }
    }
    return result
}

internal fun cameraJpeg(bitmap: Bitmap, crop: Rect, rotation: Int): ByteArray? {
    val scale = minOf(1f, 1280f / max(crop.width(), crop.height()), 720f / min(crop.width(), crop.height()))
    val transform = Matrix().apply { setScale(scale, scale); postRotate(rotation.toFloat()) }
    val image = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height(), transform, true)
    return try {
        val output = ByteArrayOutputStream()
        for (quality in listOf(82, 65, 45)) {
            output.reset()
            if (image.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= 500_000)
                return output.toByteArray()
        }
        null
    } finally {
        if (image !== bitmap) image.recycle()
    }
}
