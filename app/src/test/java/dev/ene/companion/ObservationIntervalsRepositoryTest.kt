package dev.ene.companion

import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ObservationIntervalsRepositoryTest {
    @Test fun contextTurnsOnlyUseConfirmedValuesAndOldPcOmitsThem() {
        val shared = SharedObservationIntervals { 0L }
        val outgoing = mutableListOf<WireMessage>()
        val c = ExtensionContext(1, epoch, id(40), conversation)
        shared.connect(c) { outgoing.add(it) }
        val get = outgoing.single() as ObservationIntervalsGet
        val old = ObservationIntervalsState(1, epoch, id(40), conversation, 30, 300, 1, true, get.query_id)
        shared.receive(old)
        assertNull(shared.state.value.intervals.visualContextTurns)
        shared.save(12, 90)
        val oldSet = outgoing.last() as ObservationIntervalsSet
        assertFalse(ProtocolCodec.encode(oldSet).contains("visual_context_turns"))
        shared.receive(old.copy(query_id = oldSet.query_id))
        shared.receive(old.copy(query_id = null, revision = 2, visual_context_turns = 5))
        shared.save(30, 300, 0)
        val update = outgoing.last() as ObservationIntervalsSet
        assertEquals(0, update.visual_context_turns)
        assertEquals(5, shared.state.value.intervals.visualContextTurns)
        shared.receive(old.copy(query_id = update.query_id, revision = 3, visual_context_turns = 0))
        assertEquals(0, shared.state.value.intervals.visualContextTurns)
        assertEquals(update, ProtocolCodec.decode(ProtocolCodec.encode(update)))
        for (invalid in listOf(-1, 21)) assertThrows(ProtocolException::class.java) {
            ProtocolCodec.encode(update.copy(visual_context_turns = invalid))
        }
    }

    @Test fun lookupTimeoutCanRetryAndDiscardsRepliesFromThePreviousConnection() {
        var now = 0L
        val shared = SharedObservationIntervals { now }
        val outgoing = mutableListOf<WireMessage>()
        val c = ExtensionContext(1, epoch, id(40), conversation)
        shared.connect(c) { outgoing.add(it) }
        val first = outgoing.single() as ObservationIntervalsGet
        now = 5000; shared.tick()
        assertTrue(shared.state.value.error)
        shared.refresh()
        val second = outgoing.last() as ObservationIntervalsGet
        val current = ObservationIntervalsState(1, epoch, id(40), conversation, 20, 80, 2, true, second.query_id)
        shared.receive(current.copy(query_id = first.query_id))
        shared.receive(current.copy(connection_generation = id(41)))
        assertFalse(shared.state.value.loaded)
        shared.receive(current)
        assertEquals(ObservationIntervals(20, 80), shared.state.value.intervals)
        shared.baseChanged(epoch, id(42))
        shared.receive(current.copy(query_id = null))
        assertFalse(shared.state.value.supported)
    }

    @Test fun settingsDoNotRequireCameraAndOnlyUseConfirmedValues() = runTest {
        val transport = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("observation_intervals_v1") }
        val thread = StandardTestDispatcher(testScheduler)
        val repo = ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, thread, thread, thread,
            nowMillis = { testScheduler.currentTime }, jitter = { 0.0 })
        fun state(revision: Long, sample: Int, request: Int, saved: Boolean = true, query: String? = null) =
            ObservationIntervalsState(1, epoch, id(40), conversation, sample, request, revision, saved, query)
        try {
            repo.foreground(true); runCurrent()
            SessionFixtures.frames(emptyList()).forEach(transport.socket::offer); runCurrent()
            val get = transport.socket.sent.filterIsInstance<ObservationIntervalsGet>().single()
            assertFalse(repo.observationIntervals.value.loaded)
            transport.socket.offer(state(2, 16, 120, query = get.query_id)); runCurrent()
            assertEquals(ObservationIntervals(16, 120), repo.observationIntervals.value.intervals)
            repo.setObservationIntervals(3, 20); runCurrent()
            val set = transport.socket.sent.filterIsInstance<ObservationIntervalsSet>().single()
            assertEquals(3, set.sample_seconds)
            assertTrue(repo.observationIntervals.value.saving)
            assertEquals(120, repo.observationIntervals.value.intervals.requestSeconds)
            transport.socket.offer(state(4, 50, 400))
            transport.socket.offer(state(3, 3, 20, query = set.query_id)); runCurrent()
            assertFalse(repo.observationIntervals.value.saving)
            assertEquals(ObservationIntervals(50, 400), repo.observationIntervals.value.intervals)
            repo.setObservationIntervals(9, 10); runCurrent()
            val retry = transport.socket.sent.filterIsInstance<ObservationIntervalsSet>().last()
            transport.socket.offer(state(4, 50, 400, saved = false, query = retry.query_id)); runCurrent()
            assertTrue(repo.observationIntervals.value.error)
            assertEquals(ObservationIntervals(50, 400), repo.observationIntervals.value.intervals)
            assertTrue(transport.socket.sent.none { it is CameraShare })
            repo.foreground(false); runCurrent()
            assertFalse(repo.observationIntervals.value.loaded)
        } finally { repo.close(); runCurrent() }
    }
}
