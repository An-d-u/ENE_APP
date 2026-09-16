package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioLifecycleTest {
    private suspend fun TestScope.prepare(f: AudioFixture) {
        f.activate(); f.extension.receive(f.offer()); runCurrent()
        f.media.single().chunks.send(ByteArray(9600)); runCurrent()
        assertEquals(1, f.sent.filterIsInstance<AudioPrepared>().size)
    }

    @Test fun pauseImmediatelyStopsAndLateStartOrFocusCannotResume() = runTest {
        val f = AudioFixture(this)
        try {
            prepare(f)
            f.extension.resumed(false)
            assertEquals(1, f.platform.sinks.single().stops)
            f.extension.receive(f.start())
            f.platform.focuses.single().change("gained")
            runCurrent()
            assertEquals(0, f.platform.sinks.single().starts)
            assertEquals(1, f.platform.sinks.single().releases)
            assertFalse(f.sent.filterIsInstance<AudioAvailability>().last().available)
            assertEquals(1, f.sent.filterIsInstance<AudioCancel>().size)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun rotationKeepsPlayingOwnerButBlocksNewPermission() = runTest {
        val f = AudioFixture(this)
        try {
            prepare(f); f.extension.receive(f.start())
            val sink = f.platform.sinks.single()
            f.extension.resumed(false, changingConfigurations = true)
            assertEquals(0, sink.stops)
            f.extension.receive(f.offer().copy(utterance_id = audioId(90)))
            assertEquals(1, f.sent.filterIsInstance<AudioRejected>().size)
            f.extension.resumed(true)
            assertEquals(1, sink.starts)
            assertEquals(1, f.platform.sinks.size)
            f.media.single().chunks.close(); f.extension.receive(f.end(4800)); runCurrent()
            sink.head = 4800; advanceTimeBy(50); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioFinished>().size)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun sameConversationResyncKeepsVoiceButNewConversationCancelsIt() = runTest {
        val f = AudioFixture(this)
        try {
            prepare(f); f.extension.receive(f.start())
            val sink = f.platform.sinks.single()
            f.extension.baseState(audioId(1), audioId(3), false)
            assertEquals(0, sink.stops)
            f.extension.baseState(audioId(1), audioId(30), false)
            assertEquals(1, sink.stops)
            f.extension.receive(f.start())
            runCurrent()
            assertEquals(1, sink.starts)
            assertEquals(1, sink.releases)
            assertTrue(f.media.single().done)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun focusLossAndSocketCloseCompeteForOnlyOneCleanup() = runTest {
        val f = AudioFixture(this)
        prepare(f); f.extension.receive(f.start())
        val sink = f.platform.sinks.single()
        f.platform.focuses.single().change("transient")
        f.extension.shutdown()
        f.platform.focuses.single().noisy()
        f.extension.closeAndJoin()
        assertEquals(1, sink.stops)
        assertEquals(1, sink.releases)
        assertEquals(1, f.platform.focuses.single().abandons)
        assertEquals(1, f.media.single().closes)
        assertTrue(f.media.single().done)
    }

    @Test fun missingProgressAckStopsPlaybackWithoutLosingBaseConnection() = runTest {
        val f = AudioFixture(this)
        try {
            prepare(f); f.extension.receive(f.start())
            val sink = f.platform.sinks.single()
            repeat(49) {
                sink.head = (it + 1) * 50
                advanceTimeBy(100); runCurrent()
            }
            assertEquals(0, sink.stops)
            advanceTimeBy(100); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioCancel>().size)
            assertEquals(1, sink.releases)
            assertTrue(f.failures.isEmpty())
        } finally { f.extension.closeAndJoin() }
    }
}
