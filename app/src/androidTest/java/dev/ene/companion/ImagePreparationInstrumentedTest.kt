package dev.ene.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.ene.companion.input.ImagePreparation
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ImagePreparationInstrumentedTest {
    @Test fun syntheticTransparentPixelsSurviveBoundedReencoding() {
        val source = Bitmap.createBitmap(4096, 2048, Bitmap.Config.ARGB_8888)
        source.eraseColor(0x644080C0)
        val output = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, output)
        source.recycle()
        val image = ImagePreparation.prepare(ByteArrayInputStream(output.toByteArray()))
        assertEquals("image/png", image.mime)
        assertTrue(image.bytes.size <= 4 * 1024 * 1024)
        assertTrue(image.width <= 2048 && image.height <= 2048)
        val restored = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertEquals(100, restored.getPixel(0, 0).ushr(24))
        restored.recycle()
    }
}
