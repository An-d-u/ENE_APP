package dev.ene.companion.camera

import dev.ene.companion.connection.ObservationIntervals
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CameraFrameSamplerTest {
    @Test fun questionUsesNextFrameAndClosingCancelsPendingCapture() = runTest {
        val sampler = CameraFrameSampler { ObservationIntervals() }
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
        val sampler = CameraFrameSampler { ObservationIntervals() }
        val uploads = mutableListOf<Int>()
        var samples = 0
        fun frame(time: Long, color: Int, allowed: Boolean = true) {
            sampler.frame(time, { if (allowed) 1L else null }, { samples++; IntArray(144) { color } },
                { byteArrayOf(color.toByte()) }) { _, bytes -> uploads += bytes.single().toInt() and 255 }
        }
        frame(0, 0)
        frame(1_000, 200)
        frame(29_999, 200)
        assertEquals(1, samples)
        assertTrue(uploads.isEmpty())
        frame(30_000, 200)
        frame(60_000, 0)
        frame(90_000, 0)
        assertEquals(4, samples)
        assertEquals(listOf(200), uploads)
        frame(330_000, 0)
        assertEquals(listOf(200, 0), uploads)
        frame(331_000, 0, false)
        val before = samples
        frame(660_000, 0, false)
        assertEquals(before, samples)
        frame(690_000, 200)
        assertEquals(listOf(200, 0), uploads)
    }

    @Test fun revokeDuringEncodingDropsQuestionAndObservation() = runTest {
        val sampler = CameraFrameSampler { ObservationIntervals() }
        sampler.frame(0, { 1L }, { intArrayOf(0) }, { error("첫 장") }) { _, _ -> fail() }
        val question = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        sampler.frame(30_000, { 1L }, { intArrayOf(200) }, { sampler.close(); byteArrayOf(1) }) { _, _ -> fail() }
        assertNull(question.await())
    }

    @Test fun liveIntervalsCountFromActualSampleAndUpload() {
        var intervals = ObservationIntervals(sampleSeconds = 5, requestSeconds = 60)
        val sampler = CameraFrameSampler { intervals }
        val samples = mutableListOf<Long>()
        val uploads = mutableListOf<Long>()
        fun frame(time: Long, value: Int) {
            sampler.frame(time, { 1L }, { samples += time; intArrayOf(value) },
                { byteArrayOf(1) }) { _, _ -> uploads += time }
        }
        frame(0, 0)
        frame(5_000, 200)
        intervals = ObservationIntervals(sampleSeconds = 10, requestSeconds = 20)
        frame(10_000, 0)
        frame(15_000, 0)
        frame(25_000, 0)
        assertEquals(listOf(0L, 5_000L, 15_000L, 25_000L), samples)
        assertEquals(listOf(5_000L, 25_000L), uploads)
        intervals = ObservationIntervals(sampleSeconds = 1, requestSeconds = 60)
        frame(26_000, 200)
        frame(84_000, 200)
        assertEquals(2, uploads.size)
        frame(85_000, 200)
        assertEquals(85_000L, uploads.last())
    }

    @Test fun aChangeThatDisappearsDuringCooldownDoesNotTriggerAStaleUpload() {
        val sampler = CameraFrameSampler { ObservationIntervals(1, 10) }
        val uploads = mutableListOf<Long>()
        fun frame(time: Long, value: Int) {
            sampler.frame(time, { 1L }, { intArrayOf(value) }, { byteArrayOf(1) }) { _, _ -> uploads += time }
        }
        frame(0, 0)
        frame(1_000, 200)
        frame(5_000, 0)
        frame(11_000, 200)
        assertEquals(listOf(1_000L), uploads)
    }

    @Test fun returningToPhoneDropsOldObservationWithoutCancellingExplicitQuestion() = runTest {
        val sampler = CameraFrameSampler { ObservationIntervals() }
        var ownership = 3L
        sampler.frame(0, { ownership }, { intArrayOf(0) }, { error("첫 장") }) { _, _ -> fail() }
        val answer = async(start = CoroutineStart.UNDISPATCHED) { sampler.nextJpeg() }
        sampler.frame(30_000, { ownership }, { intArrayOf(220) },
            { ownership = 5L; byteArrayOf(7) }) { _, _ -> fail("재승인 전 관찰 결과") }
        assertArrayEquals(byteArrayOf(7), answer.await())
        sampler.frame(60_000, { ownership }, { intArrayOf(10) }, { error("새 소유권 첫 장") }) { _, _ -> fail() }
        val results = mutableListOf<Long>()
        sampler.frame(90_000, { ownership }, { intArrayOf(220) }, { byteArrayOf(9) }) { epoch, _ -> results += epoch }
        assertEquals(listOf(5L), results)
    }
}
