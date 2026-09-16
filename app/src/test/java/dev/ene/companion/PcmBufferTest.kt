package dev.ene.companion

import dev.ene.companion.audio.PcmBuffer
import dev.ene.companion.audio.PcmEnvelope
import org.junit.Assert.*
import org.junit.Test

/** 음성은 모두 메모리에서 생성한 일정한 샘플이다. */
class PcmBufferTest {
    @Test fun scratchSubrangeIsCopiedOnceAndStringDoesNotExposeSamples() {
        val raw = ByteArray(24) { 37 }
        val buffer = PcmBuffer(8000, 1)
        assertEquals("accepted", buffer.offer(raw, 4, 8))
        raw.fill(0)
        val view = buffer.peek()!!
        assertArrayEquals(ByteArray(8) { 37 }, view.bytes)
        assertEquals("PcmSlice(length=8)", view.toString())
        assertEquals("invalid", buffer.offer(raw, -1, 8))
        assertEquals("invalid", buffer.offer(raw, 20, 8))
    }
    private fun pcm(frames: Int, channels: Int, value: Int): ByteArray =
        ByteArray(frames * channels * 2) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() }

    @Test fun nativeAndAppQueuesShareFourSecondLimit() {
        val buffer = PcmBuffer(8000, 1, reservedBytes = 32000)
        assertEquals("accepted", buffer.offer(ByteArray(32000)))
        assertEquals("full", buffer.offer(ByteArray(2)))
        assertEquals(32000, buffer.retainedBytes)
        buffer.consume(16000)
        assertEquals("full", buffer.offer(ByteArray(2)))
        buffer.consume(16000)
        assertEquals("accepted", buffer.offer(ByteArray(2)))
    }

    @Test fun inputMutationCannotCorruptQueueAndViewsDoNotCopyAgain() {
        val bytes = pcm(10, 2, 123)
        val buffer = PcmBuffer(48000, 2)
        buffer.offer(bytes)
        bytes.fill(0)
        val first = buffer.peek()!!
        assertEquals(123.toByte(), first.bytes[0])
        buffer.consume(4)
        val second = buffer.peek()!!
        assertSame(first.bytes, second.bytes)
        assertEquals(4, second.offset)
        assertEquals(36, second.length)
    }

    @Test fun invalidChunksAndTooManyTinyChunksAreRejected() {
        val buffer = PcmBuffer(24000, 2)
        assertEquals("invalid", buffer.offer(ByteArray(3)))
        assertEquals("invalid", buffer.offer(ByteArray(32772)))
        repeat(256) { assertEquals("accepted", buffer.offer(ByteArray(4))) }
        assertEquals("full", buffer.offer(ByteArray(4)))
        buffer.finish()
        assertEquals("closed", buffer.offer(ByteArray(4)))
        buffer.close()
        assertNull(buffer.peek())
        assertEquals(0, buffer.retainedBytes)
    }

    @Test fun stereoRmsIsAppliedOnlyAfterActualFramesAreConsumed() {
        val envelope = PcmEnvelope(8000, 2)
        assertTrue(envelope.append(pcm(400, 2, 8192)))
        assertEquals(0f, envelope.mouthAt(399), 0f)
        assertEquals(0.75f, envelope.mouthAt(400), 0.0001f)
        assertEquals(0, envelope.pendingWindows)
        assertTrue(envelope.append(pcm(400, 2, 0)))
        assertEquals(0.75f, envelope.mouthAt(799), 0.0001f)
        assertEquals(0f, envelope.mouthAt(800), 0f)
    }

    @Test fun stereoUsesBothChannelsAndShortEofFlushesOneWindow() {
        val envelope = PcmEnvelope(48000, 2)
        val samples = ByteArray(400) { if (it % 4 == 3) 64 else 0 }
        assertTrue(envelope.append(samples))
        envelope.finish()
        assertEquals(1f, envelope.mouthAt(100), 0f)
        assertEquals(0, envelope.pendingWindows)
        assertFalse(envelope.append(samples))
    }

    @Test fun envelopeAndCumulativeSourceSizeStayBounded() {
        val envelope = PcmEnvelope(8000, 1)
        repeat(80) { assertTrue(envelope.append(pcm(400, 1, 0))) }
        assertFalse(envelope.append(pcm(400, 1, 0)))
        assertEquals(80, envelope.pendingWindows)
        val buffer = PcmBuffer(8000, 1)
        repeat(90) {
            assertEquals("accepted", buffer.offer(ByteArray(32000)))
            buffer.consume(32000)
        }
        assertEquals("invalid", buffer.offer(ByteArray(2)))
    }
}
