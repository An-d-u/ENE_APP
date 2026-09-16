package dev.ene.companion

import dev.ene.companion.character.*
import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class HeadPatSessionTest {
    private val context = ExtensionContext(1, audioId(1), audioId(2), audioId(3))
    private val model = "a".repeat(64)
    private var now = 0L
    private val sent = mutableListOf<HeadPat>()
    private val shown = mutableListOf<HeadPatState>()
    private fun session(sendOk: Boolean = true) = HeadPatSession(context,
        send = { sent += it; sendOk }, visual = shown::add, nowMillis = { now }).also { it.configure(model, true) }
    private fun input(phase: String, seq: Long, id: String = audioId(4), intensity: Double = .5) =
        HeadPatInput(model, id, seq, phase, intensity)
    private fun reply(phase: String, seq: Long, source: String = "phone", id: String = audioId(4), number: Long = 1) =
        HeadPatState(1, audioId(1), audioId(2), model, id, number, seq, phase, .5, source, phase)

    @Test fun repeatedMovesCoalesceToTenPerSecondAndEndIsNeverRetried() {
        val value = session()
        assertTrue(value.input(input("start", 0)))
        for (seq in 1L..50L) { now = seq; value.input(input("update", seq, intensity = seq / 100.0)) }
        assertEquals(listOf("start"), sent.map { it.phase })
        now = 100; value.tick()
        assertEquals(listOf("start", "update"), sent.map { it.phase })
        assertEquals(.5, sent.last().intensity, .0001)
        assertTrue(value.input(input("end", 51)))
        assertFalse(value.input(input("end", 51)))
        value.tick()
        assertEquals(listOf(0L, 1L, 2L), sent.map { it.seq })
    }

    @Test fun numbersSurviveRendererResetAndOldIdCannotStartAgain() {
        val value = session()
        value.input(input("start", 0)); value.cancel()
        assertFalse(value.input(input("start", 0)))
        value.input(input("start", 0, audioId(5)))
        assertEquals(listOf(1L, 1L, 2L), sent.map { it.interaction_no })
        value.configure("b".repeat(64), true)
        assertEquals("cancel", sent.last().phase)
        assertFalse(value.input(input("end", 1, audioId(5))))
    }

    @Test fun staleInputAndTransportFailureCannotKeepPredictionAlive() {
        val value = session(false)
        assertFalse(value.input(input("start", 0)))
        assertEquals("cancelled", shown.last().phase)
        assertFalse(value.input(input("update", 1)))
        now = 1000; value.tick()
        assertEquals(1, sent.size)
    }

    @Test fun duplicateOwnEchoDoesNotRestartPredictionAndBusyCancelsIt() {
        val value = session()
        value.input(input("start", 0))
        value.receive(reply("accepted", 0)); value.receive(reply("accepted", 0))
        assertTrue(shown.isEmpty())
        value.receive(reply("rejected", 1))
        assertEquals("rejected", shown.single().phase)
        assertFalse(value.input(input("end", 1)))
    }

    @Test fun oldTerminalCannotStopNewRemoteSessionAndNoAcceptedMeansNoReplay() {
        val value = session()
        value.receive(reply("update", 1, "pc"))
        assertTrue(shown.isEmpty())
        value.receive(reply("accepted", 0, "pc", number = 2))
        value.receive(reply("ended", 2, "pc", number = 1))
        assertEquals(listOf("accepted"), shown.map { it.phase })
        now = 2000; value.tick()
        assertEquals("cancelled", shown.last().phase)
        assertEquals(2L, shown.last().interaction_no)
    }

    @Test fun stationaryHeartbeatKeepsSessionButMissingInputExpires() {
        val value = session()
        value.input(input("start", 0))
        for (seq in 1L..5L) { now = seq * 500; value.input(input("update", seq)); value.tick() }
        assertEquals(6, sent.size)
        now += 2000; value.tick()
        assertEquals("cancel", sent.last().phase)
        assertEquals("cancelled", shown.last().phase)
        assertFalse(value.input(input("end", 6)))
    }

    @Test fun malformedOrWrongModelInputIsNotForwarded() {
        val value = session()
        for (bad in listOf(input("start", 0).copy(modelVersion = "b".repeat(64)),
            input("start", 0).copy(intensity = Double.NaN), input("start", -1),
            input("start", 0).copy(interactionId = "invalid"))) assertFalse(value.input(bad))
        assertTrue(sent.isEmpty())
    }

    @Test fun hiddenAcceptedEventIsConsumedWithoutReplayingAfterResume() {
        val value = session()
        value.receive(reply("accepted", 0, "pc"), displayAllowed = false)
        value.receive(reply("accepted", 0, "pc"))
        value.receive(reply("update", 1, "pc"))
        assertTrue(shown.isEmpty())
    }
}
