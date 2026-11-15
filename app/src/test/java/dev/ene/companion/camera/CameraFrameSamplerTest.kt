package dev.ene.companion.camera

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CameraFrameSamplerTest {
    @Test fun questionUsesNextFrameAndClosingCancelsPendingCapture() = runTest {
        val sampler = CameraFrameSampler()
        var encodes = 0
        fun frame(value: Int) = sampler.frame(0, { null }, { error("관찰 비활성") },
            { encodes++; byteArrayOf(value.toByte()) }) { _, _ -> error("관찰 비활성") }
        frame(1)
        val answer = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        frame(2)
        assertArrayEquals(byteArrayOf(2), answer.await())
        frame(3)
        assertEquals(1, encodes)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        sampler.close()
        frame(4)
        assertNull(cancelled.await())
        assertNull(sampler.nextJpeg())
        assertEquals(1, encodes)
    }

    @Test fun staticScenesBudgetAndOwnerChanges() {
        val sampler = CameraFrameSampler()
        val uploads = mutableListOf<Int>()
        var samples = 0
        fun frame(time: Long, color: Int, allowed: Boolean = true) {
            sampler.frame(time, { if (allowed) 1L else null }, { samples++; IntArray(144) { color } },
                { byteArrayOf(color.toByte()) }) { _, bytes -> uploads += bytes.single().toInt() and 255 }
        }
        frame(0, 0)
        frame(1_000, 200)
        frame(15_000, 0)
        assertTrue(uploads.isEmpty())
        frame(30_000, 200)
        frame(45_000, 0)
        frame(60_000, 0)
        assertEquals(listOf(200), uploads)
        frame(90_000, 0)
        assertEquals(listOf(200, 0), uploads)
        frame(91_000, 0, false)
        val before = samples
        frame(150_000, 0, false)
        assertEquals(before, samples)
        frame(180_000, 200)
        assertEquals(listOf(200, 0), uploads)
    }

    @Test fun revokeDuringEncodingDropsQuestionAndObservation() = runTest {
        val sampler = CameraFrameSampler()
        sampler.frame(0, { 1L }, { intArrayOf(0) }, { error("첫 장") }) { _, _ -> fail() }
        val question = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        sampler.frame(15_000, { 1L }, { intArrayOf(200) }, { sampler.close(); byteArrayOf(1) }) { _, _ -> fail() }
        assertNull(question.await())
    }

    @Test fun returningToPhoneDropsOldObservationWithoutCancellingExplicitQuestion() = runTest {
        val sampler = CameraFrameSampler()
        var ownership = 3L
        sampler.frame(0, { ownership }, { intArrayOf(0) }, { error("첫 장") }) { _, _ -> fail() }
        val answer = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        sampler.frame(15_000, { ownership }, { intArrayOf(220) },
            { ownership = 5L; byteArrayOf(7) }) { _, _ -> fail("재승인 전 관찰 결과") }
        assertArrayEquals(byteArrayOf(7), answer.await())
        sampler.frame(30_000, { ownership }, { intArrayOf(10) }, { error("새 소유권 첫 장") }) { _, _ -> fail() }
        val results = mutableListOf<Long>()
        sampler.frame(45_000, { ownership }, { intArrayOf(220) }, { byteArrayOf(9) }) { epoch, _ -> results += epoch }
        assertEquals(listOf(5L), results)
    }
}
