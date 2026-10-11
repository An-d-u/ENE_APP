package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ChatProgressStateTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val caps = listOf("chat_progress_v1")
    private val ready = Ready(id(1), id(2), id(3), 1, caps)
    private var now = 0L
    private fun state() = ChatProgressState { now }.also {
        it.connected("synthetic-registration", ready)
        it.receive(ExtensionsReady(1, id(2), id(4), caps))
    }
    private fun ChatProgressState.synced(processing: ProcessingState = ProcessingState("responding", id(5))) =
        baseState(id(2), id(3), true, processing)
    private fun response(rev: Long = 1, phase: String = "generating", request: String? = id(5)) =
        ChatProgress(1, id(2), id(4), id(3), rev, phase, if (phase == "idle") null else request)

    @Test fun queryRequiresExtensionsAndCompletedSnapshotAndIsSentOnce() {
        val state = ChatProgressState { now }
        state.connected("synthetic-registration", ready)
        state.synced(); assertNull(state.nextRequest())
        state.receive(ExtensionsReady(1, id(2), id(4), caps))
        assertNotNull(state.nextRequest()); assertNull(state.nextRequest())
        state.baseState(id(2), id(3), false)
        assertNull(state.nextRequest())
        state.synced(); assertNull(state.nextRequest())
    }

    @Test fun earlyStateWaitsForCurrentRequestAndOldIdleCannotHideNewRequest() {
        val state = state()
        state.receive(response())
        assertNull(state.view.phase)
        state.synced(); assertEquals("generating", state.view.phase)
        state.receive(response(2, "text_ready")); assertEquals("text_ready", state.view.phase)
        state.synced(ProcessingState("responding", id(6))); assertNull(state.view.phase)
        state.receive(response(3, "idle")); assertNull(state.view.phase)
        state.receive(response(4, request = id(6))); assertEquals("generating", state.view.phase)
    }

    @Test fun timeoutDoesNotPollAndLateStateRecovers() {
        val state = state(); state.synced()
        now = 10000
        assertNotNull(state.nextRequest())
        now = 14999; assertNull(state.nextRequest()); assertEquals("syncing", state.view.status)
        now = 15000; assertNull(state.nextRequest()); assertEquals("error", state.view.status)
        repeat(20) { now += 250; assertNull(state.nextRequest()) }
        state.receive(response()); assertEquals("ready", state.view.status)
    }

    @Test fun lowerRevisionIsIgnoredAndConflictingRevisionNeedsNewerRecovery() {
        val state = state(); state.synced(); state.nextRequest()
        state.receive(response(8, "text_ready"))
        state.receive(response(7)); assertEquals("text_ready", state.view.phase)
        state.receive(response(8)); assertEquals("error", state.view.status); assertNull(state.view.phase)
        state.receive(response(8, "text_ready")); assertEquals("error", state.view.status)
        state.receive(response(9, "text_ready")); assertEquals("ready", state.view.status)
    }

    @Test fun reconnectAndNewConversationResetRevisionButRejectOldContext() {
        val state = state(); state.synced(); state.nextRequest(); state.receive(response(10))
        state.disconnected(); state.receive(response(11)); assertNull(state.view.phase)
        state.connected("synthetic-registration", ready)
        state.receive(ExtensionsReady(1, id(2), id(7), caps)); state.synced()
        state.receive(response(20)); assertNull(state.view.phase)
        state.receive(response(0).copy(connection_generation = id(7)))
        assertEquals("generating", state.view.phase)
        state.baseState(id(2), id(8), false)
        state.receive(response(21).copy(connection_generation = id(7)))
        assertNull(state.view.phase)
        state.baseState(id(2), id(8), true, ProcessingState("idle"))
        assertEquals(id(8), state.nextRequest()!!.conversation_id)
        state.receive(response(0, "idle").copy(connection_generation = id(7), conversation_id = id(8)))
        assertEquals("idle", state.view.phase)
        state.forget(); assertNull(state.nextRequest()); assertNull(state.view.phase)
    }

    @Test fun otherFeatureErrorsAreIgnoredAndProgressErrorIsNonfatal() {
        val mixed = caps + "chat_display_v1"
        val state = ChatProgressState { now }
        state.connected("synthetic-registration", ready.copy(capabilities = mixed))
        state.receive(ExtensionsReady(1, id(2), id(4), mixed)); state.synced(); state.nextRequest()
        state.receive(response())
        state.receive(ExtensionError(1, id(2), id(4), "chat_display_v1", "synthetic_failure"))
        assertEquals("generating", state.view.phase)
        state.receive(ExtensionError(1, id(2), id(4), "chat_progress_v1", "progress_unavailable"))
        assertEquals("error", state.view.status); assertNull(state.view.phase)
        state.receive(response(2)); assertEquals("ready", state.view.status)
    }

    @Test fun wrongNegotiationAndOldPcNeverApplyProgress() {
        val state = ChatProgressState { now }
        state.connected("synthetic-registration", ready)
        state.receive(ExtensionsReady(2, id(2), id(4), caps))
        state.receive(ExtensionsReady(1, id(2), id(4), caps + "chat_display_v1"))
        state.synced(); assertNull(state.nextRequest())
        state.receive(ExtensionsReady(1, id(2), id(4), caps))
        assertNotNull(state.nextRequest())
        for (bad in listOf(response().copy(registration_generation = 2), response().copy(server_epoch = id(9)),
            response().copy(conversation_id = id(9)), response().copy(connection_generation = id(9)))) state.receive(bad)
        assertNull(state.view.phase)
        state.connected("synthetic-registration", ready.copy(capabilities = emptyList()))
        state.synced(); state.receive(response()); now += 20000
        assertEquals("unsupported", state.view.status); assertNull(state.nextRequest())
    }
}
