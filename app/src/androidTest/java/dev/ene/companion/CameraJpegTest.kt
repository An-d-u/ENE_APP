package dev.ene.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ene.companion.camera.cameraJpeg
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraJpegTest {
    @Test fun cropAndRotateTheSelectedSyntheticFrameInMemory() {
        val input = Bitmap.createBitmap(1600, 840, Bitmap.Config.ARGB_8888)
        input.eraseColor(Color.BLUE)
        val canvas = android.graphics.Canvas(input)
        val paint = android.graphics.Paint().apply { color = Color.RED }
        canvas.drawRect(100f, 20f, 800f, 820f, paint)
        val bytes = requireNotNull(cameraJpeg(input, Rect(100, 20, 1500, 820), 90))
        assertTrue(bytes.size <= 500_000)
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(720, out.width)
        assertEquals(1260, out.height)
        assertTrue(Color.red(out.getPixel(360, 40)) > 220)
        assertTrue(Color.blue(out.getPixel(360, 1220)) > 220)
        out.recycle()
        input.recycle()
    }
}
