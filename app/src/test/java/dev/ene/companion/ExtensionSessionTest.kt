package dev.ene.companion

import dev.ene.companion.audio.*
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

internal fun audioId(number: Int) = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')

internal class AudioTestPlatform : AudioPlatform {
    class Sink(override val bufferBytes: Int) : PcmSink {
        override val initialized = true
        var head = 0
        var written = 0
        var starts = 0
        var stops = 0
        var releases = 0
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int { written += length; return length }
        override fun playbackHead() = head
        override fun play() { starts++ }
        override fun pause() { stops++ }
        override fun flush() = Unit
        override fun stop() = Unit
        override fun release() { releases++ }
    }
    class Focus : AudioFocusPort {
        var grant = "granted"
        var requests = 0
        var abandons = 0
        var change: (String) -> Unit = {}
        var noisy: () -> Unit = {}
        override fun request(onChange: (String) -> Unit): String { requests++; change = onChange; return grant }
        override fun abandon() { abandons++ }
        override fun watchNoisy(onNoisy: () -> Unit) { noisy = onNoisy }
        override fun unwatchNoisy() = Unit
    }
    val sinks = mutableListOf<Sink>()
    val focuses = mutableListOf<Focus>()
    var deny = false
    override fun player(sampleRate: Int, channels: Int): PcmPlayer {
        val sink = Sink(sampleRate / 5 * channels * 2).also(sinks::add)
        return PcmPlayer(sampleRate, channels, object : PcmSinkFactory {
            override fun minimumBufferBytes(sampleRate: Int, channels: Int) = sink.bufferBytes
            override fun create(sampleRate: Int, channels: Int, bufferBytes: Int): PcmSink = sink
        })
    }
    override fun focus(interrupted: (String) -> Unit): AudioFocusController {
        val port = Focus().also { it.grant = if (deny) "denied" else "granted"; focuses += it }
        return AudioFocusController(port, interrupted)
    }
}

internal open class TestAudioMedia : AudioMedia {
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    var closes = 0
    var done = false
    override suspend fun readAudio(ref: AudioRef, sampleRate: Int, channels: Int, onChunk: suspend (PcmSlice) -> Unit): Long {
        var bytes = 0L
        try {
            for (chunk in chunks) { onChunk(PcmSlice(chunk, 0, chunk.size)); bytes += chunk.size }
            return bytes / (channels * 2)
        } finally { done = true }
    }
    override fun close() { closes++; chunks.cancel() }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AudioFixture(test: TestScope, makeMedia: () -> TestAudioMedia = ::TestAudioMedia) {
    val ready = Ready(audioId(9), audioId(1), audioId(3), 1, listOf("audio_pcm_v1"))
    val platform = AudioTestPlatform()
    val media = mutableListOf<TestAudioMedia>()
    val sent = mutableListOf<WireMessage>()
    val outputs = mutableListOf<String>()
    val failures = mutableListOf<String>()
    val playback = mutableListOf<CharacterPlayback>()
    var visible = true
    var sendThrows = false
    val extension = ExtensionSession(ready, test.backgroundScope,
        mediaFactory = { makeMedia().also(media::add) }, platform = platform,
        send = { if (sendThrows) throw IllegalStateException("synthetic"); sent += it; true }, nowMillis = { test.testScheduler.currentTime },
        isPublicAssistant = { visible && it == audioId(4) }, onOutput = outputs::add, onFailure = failures::add,
        onPlayback = playback::add,
    )
    fun activate() {
        extension.receive(ExtensionsReady(1, audioId(1), audioId(2), listOf("audio_pcm_v1")))
        extension.resumed(true)
        extension.baseState(audioId(1), audioId(3), true)
    }
    fun offer() = AudioOffer(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 24000, 1, 2, 2000)
    fun start() = AudioStart(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6))
    fun end(frames: Long) = AudioSourceEnd(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), frames)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionSessionTest {
    @Test fun characterReceivesOnlyStartedConsumedPcmAndImmediateStop() = runTest {
        val f = AudioFixture(this)
        try {
            f.activate(); f.extension.receive(f.offer()); runCurrent()
            f.media.single().chunks.send(ByteArray(9600) { if (it % 2 == 0) 0x10 else 0x27 }); runCurrent()
            assertTrue(f.playback.none { it.active })
            f.extension.receive(f.start()); runCurrent()
            assertEquals(0L, f.playback.last().played_ms)
            f.platform.sinks.single().head = 2400
            advanceTimeBy(50); runCurrent()
            assertEquals(100L, f.playback.last().played_ms)
            assertTrue(f.playback.last().mouth_open > 0.0)
            f.extension.resumed(false)
            assertFalse(f.playback.last().active)
            assertEquals(0.0, f.playback.last().mouth_open, .0001)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun contradictoryPendingEofAndDisabledStatusCannotStartLater() = runTest {
        for (disable in listOf(false, true)) {
            val f = AudioFixture(this)
            try {
                f.visible = false; f.activate(); f.extension.receive(f.offer())
                f.extension.receive(f.end(100))
                if (disable) f.extension.receive(AudioStatus(1, audioId(1), audioId(2), "pc_only", "browser_tts"))
                else f.extension.receive(f.end(101))
                f.visible = true; f.extension.baseState(audioId(1), audioId(3), true); runCurrent()
                assertEquals(1, f.sent.filterIsInstance<AudioRejected>().size)
                assertTrue(f.media.isEmpty())
            } finally { f.extension.closeAndJoin() }
        }
    }

    @Test fun sendExceptionClosesSessionWithoutEscapingMainCallback() = runTest {
        val f = AudioFixture(this)
        try {
            f.activate(); f.sendThrows = true
            f.extension.resumed(false)
            assertEquals(listOf("connection_closed"), f.failures)
            f.extension.receive(f.offer()); runCurrent()
            assertTrue(f.media.isEmpty())
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun mediaCloseFailureStillCancelsReaderAndReleasesPlayer() = runTest {
        val f = AudioFixture(this) { object : TestAudioMedia() {
            override fun close() { throw IllegalStateException("synthetic") }
        } }
        f.activate(); f.extension.receive(f.offer()); runCurrent()
        f.extension.closeAndJoin()
        assertTrue(f.media.single().done)
        assertEquals(1, f.platform.sinks.single().releases)
    }

    @Test fun releaseWaitsForActualReaderCleanupAndNewOfferCannotOverlap() = runTest {
        val releaseReader = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val f = AudioFixture(this) { object : TestAudioMedia() {
            override suspend fun readAudio(ref: AudioRef, sampleRate: Int, channels: Int, onChunk: suspend (PcmSlice) -> Unit): Long {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { releaseReader.await() } }
            }
        } }
        try {
            f.activate(); f.extension.receive(f.offer()); runCurrent(); entered.await()
            f.extension.resumed(false); runCurrent()
            assertEquals(1, f.platform.sinks.single().stops)
            assertEquals(0, f.platform.sinks.single().releases)
            f.extension.resumed(true)
            f.extension.receive(f.offer().copy(utterance_id = audioId(90))); runCurrent()
            assertEquals(1, f.platform.sinks.size)
            releaseReader.complete(Unit); runCurrent()
            assertEquals(1, f.platform.sinks.single().releases)
        } finally { releaseReader.complete(Unit); f.extension.closeAndJoin() }
    }

    @Test fun readyAndSourceEndDoNotPlayUntilStartAndActualDrain() = runTest {
        val f = AudioFixture(this)
        try {
            f.activate()
            f.extension.receive(f.offer()); runCurrent()
            f.media.single().chunks.send(ByteArray(9600)); f.media.single().chunks.close()
            f.extension.receive(f.end(4800)); runCurrent(); advanceTimeBy(50); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioPrepared>().size)
            val sink = f.platform.sinks.single()
            assertEquals(0, sink.starts)
            f.extension.receive(f.start()); f.extension.receive(f.start()); runCurrent()
            assertEquals(1, sink.starts)
            assertTrue(f.sent.filterIsInstance<AudioFinished>().isEmpty())
            sink.head = 4800
            advanceTimeBy(50); runCurrent()
            assertEquals(4800, f.sent.filterIsInstance<AudioFinished>().single().played_frames)
            assertEquals(1, sink.releases)
            assertTrue(f.media.single().done)
            assertTrue(f.failures.isEmpty())
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun offerWaitsForEarlierPublicEventWithoutBlockingControlFrames() = runTest {
        val f = AudioFixture(this)
        try {
            f.visible = false; f.activate()
            f.extension.receive(f.offer()); f.extension.receive(f.end(100)); runCurrent()
            assertTrue(f.media.isEmpty())
            f.extension.receive(AudioStatus(1, audioId(1), audioId(2), "auto", "ready"))
            assertTrue(f.sent.filterIsInstance<AudioRejected>().isEmpty())
            f.visible = true; f.extension.baseState(audioId(1), audioId(3), true); runCurrent()
            f.media.single().chunks.send(ByteArray(200)); f.media.single().chunks.close(); runCurrent()
            advanceTimeBy(50); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioPrepared>().size)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun preparationRejectionDoesNotStartAndReleasesEveryResource() = runTest {
        val f = AudioFixture(this)
        try {
            f.platform.deny = true; f.activate()
            f.extension.receive(f.offer()); runCurrent()
            f.media.single().chunks.send(ByteArray(9600)); runCurrent(); advanceTimeBy(50); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioRejected>().size)
            assertEquals(0, f.platform.sinks.single().starts)
            assertEquals(1, f.platform.sinks.single().releases)
            assertEquals(1, f.media.single().closes)
            assertTrue(f.media.single().done)
        } finally { f.extension.closeAndJoin() }
    }

    @Test fun unknownMessageDeadlineAndOldConnectionNeverCreatePlayer() = runTest {
        val f = AudioFixture(this)
        try {
            f.visible = false; f.activate()
            f.extension.receive(f.offer().copy(connection_generation = audioId(99)))
            f.extension.receive(f.offer()); runCurrent()
            advanceTimeBy(2000); runCurrent()
            assertEquals(1, f.sent.filterIsInstance<AudioRejected>().size)
            assertTrue(f.media.isEmpty())
            f.visible = true; f.extension.baseState(audioId(1), audioId(3), true); runCurrent()
            assertTrue(f.media.isEmpty())
        } finally { f.extension.closeAndJoin() }
    }
}
