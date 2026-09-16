package dev.ene.companion

import dev.ene.companion.audio.AudioRef
import dev.ene.companion.audio.AudioSession
import dev.ene.companion.protocol.ExtensionContext
import org.junit.Assert.*
import org.junit.Test

/** Android API 없이 가상 시계와 출력 명령 횟수로 중복·지연 재생을 확인한다. */
class AudioSessionTest {
    private fun id(value: Int) = "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')
    private val context = ExtensionContext(1, id(1), id(2), id(3))
    private val ref = AudioRef(1, id(1), id(2), id(3), id(4), id(5), id(6))
    private fun prepared(): AudioSession = AudioSession(ref, 24000, 0).also {
        assertEquals("send_prepared", it.prepared(100, 4800, false, true))
    }
    private fun playing(): AudioSession = prepared().also {
        assertEquals("play", it.start(ref, context, 200))
    }
    private class Sink {
        var starts = 0
        fun apply(action: String) { if (action == "play") starts++ }
    }

    @Test fun offerDownloadAndPreparedNeverPlayWithoutStart() {
        val session = AudioSession(ref, 24000, 0)
        assertEquals("PREPARING", session.state)
        assertEquals("ignore", session.prepared(100, 4799, false, true))
        assertEquals("send_prepared", session.prepared(200, 4800, false, true))
        val sink = Sink()
        sink.apply(session.start(ref, context, 300))
        sink.apply(session.start(ref, context, 400))
        assertEquals(1, sink.starts)
    }

    @Test fun shortEofCanPrepareButDeniedFocusCannotWaitForLaterGain() {
        assertEquals("send_prepared", AudioSession(ref, 24000, 0).prepared(100, 100, true, true))
        val denied = AudioSession(ref, 24000, 0)
        assertEquals("send_rejected", denied.prepared(100, 4800, false, false))
        assertEquals("ignore", denied.prepared(200, 4800, false, true))
        assertEquals("ignore", denied.start(ref, context, 300))
    }

    @Test fun expiredStartAndOldGenerationCannotPlay() {
        val session = prepared()
        assertEquals("ignore", session.start(ref.copy(utteranceId = id(99)), context, 200))
        assertEquals("ignore", session.start(ref, context.copy(connectionGeneration = id(99)), 200))
        assertEquals("send_cancel", session.start(ref, context, 3100))
        assertEquals("ignore", session.start(ref, context, 3101))
    }

    @Test fun backgroundAndFocusLossNeverResume() {
        val session = playing()
        assertEquals("send_cancel", session.deactivate())
        assertEquals("ignore", session.start(ref, context, 300))
        assertEquals("ignore", session.deactivate())
    }

    @Test fun preparationAndStartWaitHaveSeparateDeadlines() {
        assertEquals("send_rejected", AudioSession(ref, 24000, 0).tick(2000))
        val session = prepared()
        assertEquals("ignore", session.tick(3099))
        assertEquals("send_cancel", session.tick(3100))
    }

    @Test fun sourceEofAndActualPlayedCountAreAllRequired() {
        val session = playing()
        assertEquals("ignore", session.finishIfDrained(100, 200, 200, true))
        assertEquals("ignore", session.finishIfDrained(200, 200, null, true))
        assertEquals("ignore", session.finishIfDrained(200, 200, 200, false))
        assertEquals("send_finished", session.finishIfDrained(200, 200, 200, true))
        assertEquals("ignore", session.finishIfDrained(200, 200, 200, true))
    }

    @Test fun unexpectedEofCancelsInsteadOfCompleting() {
        assertEquals("send_cancel", playing().finishIfDrained(100, 100, 200, true))
    }

    @Test fun unacknowledgedControlOrFrozenPlaybackStopsAfterFiveSeconds() {
        val noAck = playing()
        for (now in 300L..5100L step 100) noAck.progress(now, now)
        assertEquals("send_cancel", noAck.tick(5200))
        val frozen = playing()
        for (now in 300L..5100L step 100) {
            frozen.progress(now, 0)
            frozen.acknowledge(ref, context, 0, now)
        }
        assertEquals("send_cancel", frozen.tick(5200))
    }

    @Test fun staleOrUnsentAckCannotKeepAudioAlive() {
        val session = playing()
        assertEquals("send_progress", session.progress(300, 100))
        assertEquals("acknowledged", session.acknowledge(ref, context, 100, 350))
        assertEquals("ignore", session.acknowledge(ref, context, 100, 400))
        assertEquals("ignore", session.acknowledge(ref, context, 999, 450))
        assertEquals("ignore", session.acknowledge(ref.copy(operationId = id(99)), context, 100, 500))
    }

    @Test fun lateAckOrProgressCannotReviveAnExpiredSession() {
        val session = playing()
        session.progress(300, 100)
        assertEquals("send_cancel", session.acknowledge(ref, context, 100, 5200))
        val sink = Sink()
        sink.apply(session.start(ref, context, 5300))
        assertEquals(0, sink.starts)
        assertEquals("ignore", session.progress(5400, 200))
    }

    @Test fun progressReportsAreBoundedAndUseActualPositions() {
        val session = playing()
        assertEquals("ignore", session.progress(250, 50))
        assertEquals("send_progress", session.progress(300, 100))
        assertEquals("ignore", session.progress(350, 90))
        assertEquals("ignore", session.progress(299, 120))
        for (now in 400L..5100L step 100) session.progress(now, now)
        assertTrue(session.pendingAckCount <= 50)
    }
}
