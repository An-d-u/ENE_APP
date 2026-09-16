package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExchangeTest {
    @Test fun gracefulEndDrainsLastFrameBeforeReportingDisconnection() = runTest {
        val socket = ConnectionRepositoryTest.Socket()
        val frames = mutableListOf<WireMessage>()
        var failure: String? = null
        val job = backgroundScope.launch {
            try { exchangeSession(socket, { testScheduler.currentTime }, {}) { delay(100); frames.add(it) } }
            catch (error: ConnectionException) { failure = error.code }
        }
        val final = RequestStatus(ConnectionRepositoryTest.epoch, ConnectionRepositoryTest.conversation, ConnectionRepositoryTest.pairingId, "completed")
        socket.offer(final); socket.incoming.close(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(listOf(final), frames)
        assertEquals("connection_closed", failure)
        job.cancelAndJoin()
    }

    @Test fun heartbeatStillAnswersDuringSlowConsumption() = runTest {
        val socket = ConnectionRepositoryTest.Socket()
        val job = backgroundScope.launch { exchangeSession(socket, { testScheduler.currentTime }, {}) { delay(2000) } }
        socket.offer(SessionFixtures.frames().first()); runCurrent()
        socket.offer(Ping(ConnectionRepositoryTest.pairingId)); runCurrent()
        assertEquals(Pong(ConnectionRepositoryTest.pairingId), socket.sent.single())
        job.cancelAndJoin()
    }

    @Test fun slowConsumerHasBoundedQueueAndClosesConnection() = runTest {
        val socket = ConnectionRepositoryTest.Socket()
        var failure: String? = null
        val job = backgroundScope.launch {
            try { exchangeSession(socket, { testScheduler.currentTime }, {}) { delay(2000) } }
            catch (error: ConnectionException) { failure = error.code }
        }
        val frame = SessionFixtures.frames().first()
        repeat(130) { socket.offer(frame) }
        runCurrent(); advanceTimeBy(2001); runCurrent()
        assertEquals("slow_consumer", failure)
        assertTrue(socket.cancelled)
        job.cancelAndJoin()
    }

    @Test fun eofStopsHeartbeatBeforeFinalDecodeCrossesNextPing() = runTest {
        val delegate = ConnectionRepositoryTest.Socket()
        var ended = false
        val socket = object : CompanionSocket by delegate {
            override fun send(message: WireMessage) = !ended && delegate.send(message)
        }
        val frames = mutableListOf<WireMessage>()
        var failure: String? = null
        val job = backgroundScope.launch {
            try { exchangeSession(socket, { testScheduler.currentTime }, {}) { delay(200); frames.add(it) } }
            catch (error: ConnectionException) { failure = error.code }
        }
        runCurrent(); advanceTimeBy(14_900)
        val final = RequestStatus(ConnectionRepositoryTest.epoch, ConnectionRepositoryTest.conversation, ConnectionRepositoryTest.pairingId, "completed")
        delegate.offer(final); ended = true; delegate.incoming.close(); runCurrent()
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(final), frames)
        assertEquals("connection_closed", failure)
        assertTrue(delegate.sent.isEmpty())
        job.cancelAndJoin()
    }
}
