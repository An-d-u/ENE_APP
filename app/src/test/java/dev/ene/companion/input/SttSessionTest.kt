package dev.ene.companion.input

import dev.ene.companion.connection.InputMedia
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SttSessionTest {
    private val c = ExtensionContext(1, "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
    class Recorder : RecordingPlatform {
        var cancelled = false
        override suspend fun record(maximumBytes: Int) = ByteArray(maximumBytes)
        override fun finish() { }
        override fun cancel() { cancelled = true }
    }
    class Media : InputMedia {
        var uploads = 0
        override suspend fun upload(id: String, bytes: ByteArray) { uploads++ }
        override suspend fun thumbnail(message: String, image: String, revision: Long) = byteArrayOf()
        override fun close() { }
    }
    @Test fun maximumLengthWaitsForExplicitCompletionBeforeUpload() = runTest {
        val sent = mutableListOf<WireMessage>()
        val media = Media()
        val input = InputSession(c, media) { sent += it; true }
        var state = SttView()
        val session = SttSession(input, Recorder(), backgroundScope, { sent += it; true }, { state = it }, {})
        session.start(); runCurrent()
        val begin = sent.filterIsInstance<SttBegin>().single()
        session.receive(SttReady(1, c.serverEpoch, c.connectionGeneration, c.conversationId, begin.command_id, 100, 1000))
        runCurrent()
        assertEquals("recorded", state.phase)
        assertTrue(sent.none { it is InputReserve })
        assertEquals(0, media.uploads)
        session.finish(); runCurrent()
        val reserve = sent.filterIsInstance<InputReserve>().single()
        input.receive(InputReserved(1, c.serverEpoch, c.connectionGeneration, c.conversationId, reserve.command_id, "pcm", "00000000-0000-4000-8000-000000000009"))
        runCurrent()
        assertEquals(1, media.uploads)
        assertEquals("transcribing", state.phase)
        assertEquals(1, sent.filterIsInstance<SttTranscribe>().size)
        session.close()
    }
    @Test fun readyTimeoutReleasesBusyStateAndAllowsRetry() = runTest {
        val sent = mutableListOf<WireMessage>()
        val recorder = Recorder()
        var state = SttView()
        val session = SttSession(InputSession(c, Media()) { true }, recorder, backgroundScope, { sent += it; true }, { state = it }, {})
        session.start(); runCurrent(); advanceTimeBy(10001); runCurrent()
        assertFalse(state.busy)
        assertEquals("stt_timeout", state.reason)
        assertTrue(recorder.cancelled)
        session.start(); runCurrent()
        assertEquals(2, sent.filterIsInstance<SttBegin>().size)
        session.close()
    }
    @Test fun cancellationDiscardsLateReadyAndNeverStartsRecordingAgain() = runTest {
        val sent = mutableListOf<WireMessage>()
        var state = SttView()
        val session = SttSession(InputSession(c, Media()) { true }, Recorder(), backgroundScope, { sent += it; true }, { state = it }, {})
        session.start(); runCurrent()
        val id = sent.filterIsInstance<SttBegin>().single().command_id
        session.cancel(); runCurrent()
        session.receive(SttReady(1, c.serverEpoch, c.connectionGeneration, c.conversationId, id, 100, 1000)); runCurrent()
        assertFalse(state.busy)
        assertTrue(sent.none { it is InputReserve })
    }
}
