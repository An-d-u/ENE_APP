package dev.ene.companion

import dev.ene.companion.audio.*
import org.junit.Assert.*
import org.junit.Test

class PcmPlayerTest {
    private class Sink(override val bufferBytes: Int = 3200, override val minimumStartFrames: Int = 0) : PcmSink {
        override var initialized = true
        var head = 0
        var limit = 800
        val calls = mutableListOf<String>()
        val written = mutableListOf<Byte>()
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            calls += "write"
            val count = minOf(limit, length)
            if (count > 0) written += bytes.slice(offset until offset + count)
            return count
        }
        override fun playbackHead(): Int = head
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun flush() { calls += "flush" }
        override fun stop() { calls += "stop" }
        override fun release() { calls += "release" }
    }

    private fun factory(sink: Sink, minimum: Int = 3200) = object : PcmSinkFactory {
        override fun minimumBufferBytes(sampleRate: Int, channels: Int) = minimum
        override fun create(sampleRate: Int, channels: Int, bufferBytes: Int): PcmSink = sink
    }

    @Test fun prefillIsNonblockingAndDoesNotPlayBeforePermission() {
        val sink = Sink().apply { limit = 0 }
        val player = PcmPlayer(8000, 1, factory(sink))
        try {
            assertEquals("accepted", player.offer(ByteArray(3200)))
            player.pump()
            assertTrue(player.ready)
            assertEquals(0, player.writtenFrames)
            assertFalse(sink.calls.contains("play"))
            assertTrue(player.start())
            assertFalse(player.start())
            assertEquals(1, sink.calls.count { it == "play" })
        } finally { player.close() }
    }

    @Test fun partialWritesPreserveBytesAndDrainUsesActualHead() {
        val sink = Sink()
        val player = PcmPlayer(8000, 1, factory(sink))
        val pcm = ByteArray(3200) { if (it % 2 == 1) 32 else 0 }
        try {
            player.offer(pcm)
            player.httpEof()
            assertTrue(player.start())
            player.pump()
            assertArrayEquals(pcm, sink.written.toByteArray())
            assertEquals(1600, player.writtenFrames)
            assertFalse(player.drained(1600))
            sink.head = 1600
            val sample = player.pump()
            assertEquals(1600, sample.playedFrames)
            assertEquals(0.75f, sample.mouthOpen, 0.0001f)
            assertTrue(player.drained(1600))
            assertFalse(player.drained(1601))
        } finally { player.close() }
    }

    @Test fun nativeScratchAndAppShareOneMemoryBudget() {
        val player = PcmPlayer(8000, 1, factory(Sink()))
        try {
            assertEquals(64000, player.queueCapacityBytes + player.reservedBytes)
            assertEquals(3200 + 32768 + 8192 + 1024, player.reservedBytes)
            assertTrue(player.maxChunkBytes <= player.queueCapacityBytes)
            assertEquals("accepted", player.offer(ByteArray(player.queueCapacityBytes)))
            assertEquals("full", player.offer(ByteArray(2)))
        } finally { player.close() }
    }

    @Test fun uninitializedOrOversizedNativeBufferIsReleased() {
        for (sink in listOf(Sink().apply { initialized = false }, Sink(64000))) {
            assertThrows(PcmPlayerException::class.java) { PcmPlayer(8000, 1, factory(sink)) }
            assertEquals(1, sink.calls.count { it == "release" })
        }
        assertThrows(PcmPlayerException::class.java) { PcmPlayer(8000, 1, factory(Sink(), -1)) }
    }

    @Test fun errorsAndInterruptedTrackAreNeverResumed() {
        val sink = Sink().apply { limit = -6 }
        val player = PcmPlayer(8000, 1, factory(sink))
        player.offer(ByteArray(3200))
        assertThrows(PcmPlayerException::class.java) { player.pump() }
        player.stop()
        player.stop()
        assertFalse(player.start())
        assertEquals("closed", player.offer(ByteArray(2)))
        assertFalse(sink.calls.contains("release"))
        player.close()
        player.close()
        assertEquals(listOf("pause", "flush", "stop", "release"), sink.calls.filter { it != "write" })
    }

    @Test fun shortEofCanPrepareButUnconsumedAudioCannotFinish() {
        val sink = Sink()
        val player = PcmPlayer(8000, 1, factory(sink))
        try {
            player.offer(ByteArray(200))
            assertFalse(player.ready)
            player.httpEof()
            assertTrue(player.ready)
            player.start()
            player.pump()
            assertFalse(player.drained(100))
            sink.head = 100
            player.pump()
            assertTrue(player.drained(100))
        } finally { player.close() }
    }

    @Test fun legacyStartThresholdPaddingDoesNotChangeSourceFramesOrMouth() {
        val sink = Sink(minimumStartFrames = 1600)
        val player = PcmPlayer(8000, 1, factory(sink))
        try {
            player.offer(ByteArray(200) { if (it % 2 == 1) 32 else 0 })
            player.httpEof()
            player.start()
            player.pump()
            assertTrue(sink.written.size >= 3200)
            assertEquals(100, player.receivedFrames)
            assertEquals(100, player.writtenFrames)
            sink.head = sink.written.size / 2
            val sample = player.pump()
            assertEquals(100, sample.playedFrames)
            assertEquals(0.75f, sample.mouthOpen, 0.0001f)
            assertTrue(player.drained(100))
        } finally { player.close() }
    }

    @Test fun actualFiveSecondStallCancelsInsteadOfReportingSyntheticProgress() {
        val sink = Sink()
        val player = PcmPlayer(8000, 1, factory(sink))
        val ref = AudioRef(1, "epoch", "connection", "conversation", "message", "operation", "utterance")
        val context = dev.ene.companion.protocol.ExtensionContext(1, "epoch", "connection", "conversation")
        val session = AudioSession(ref, 8000, 0)
        try {
            player.offer(ByteArray(3200))
            assertEquals("send_prepared", session.prepared(0, player.bufferedFrames, false, true))
            assertEquals("play", session.start(ref, context, 0))
            player.start()
            assertEquals("send_progress", session.progress(100, player.pump().playedFrames))
            assertEquals("acknowledged", session.acknowledge(ref, context, 0, 100))
            assertEquals("send_cancel", session.progress(5000, player.pump().playedFrames))
            player.stop()
            assertFalse(player.start())
        } finally { player.close() }
    }
}
