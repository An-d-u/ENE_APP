package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ChatDisplayStateTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val caps = listOf("chat_display_v1")
    private val ready = Ready(id(1), id(2), id(3), 1, caps)
    private var now = 0L
    private fun active() = ChatDisplayState { now }.also {
        it.connected("synthetic-registration", ready)
        it.receive(ExtensionsReady(1, id(2), id(4), caps))
    }
    private fun response(revision: Long = 1, enabled: Boolean = true) = ChatDisplaySettings(1, id(2), id(4), revision, enabled)

    @Test fun oneRequestAndRevisionOrderingIncludingEqualRefreshRecovery() {
        val state = active()
        assertNotNull(state.nextRequest()); assertNull(state.nextRequest())
        state.receive(response(3)); assertTrue(state.view.splitEnabled)
        state.receive(response(2, false)); assertTrue(state.view.splitEnabled)
        state.refresh(); assertNotNull(state.nextRequest())
        state.refresh(); assertNull(state.nextRequest())
        now = 5001; assertNull(state.nextRequest()); assertEquals("error", state.view.status)
        state.receive(response(3)); assertEquals("ready", state.view.status)
        assertNull(state.nextRequest())
        state.receive(response(3, false)); assertEquals("error", state.view.status)
        assertTrue(state.view.splitEnabled)
        state.receive(response(4, false)); assertEquals(ChatDisplayViewState("ready", false), state.view)
    }

    @Test fun timeoutIsNonPollingAndLateValidStateRecovers() {
        val state = active(); assertNotNull(state.nextRequest())
        now = 5000; assertNull(state.nextRequest()); assertEquals("error", state.view.status)
        repeat(20) { now += 250; assertNull(state.nextRequest()) }
        state.receive(response()); assertEquals("ready", state.view.status)
        state.refresh(); assertNotNull(state.nextRequest())
    }

    @Test fun reconnectResetsRevisionButKeepsOnlySameRegistrationValue() {
        val state = active(); state.nextRequest(); state.receive(response(9))
        state.disconnected(); assertTrue(state.view.splitEnabled)
        state.receive(response(10, false)); assertTrue(state.view.splitEnabled)
        state.connected("synthetic-registration", ready)
        state.receive(ExtensionsReady(1, id(2), id(7), caps)); state.nextRequest()
        state.receive(response(10, false)); assertEquals("syncing", state.view.status)
        state.receive(response(0, false).copy(connection_generation = id(7)))
        assertEquals(ChatDisplayViewState("ready", false), state.view)
        state.receive(response(1).copy(connection_generation = id(7))); state.disconnected()
        state.connected("another-synthetic-registration", ready)
        assertFalse(state.view.splitEnabled)
        state.forget(); assertFalse(state.view.splitEnabled); assertNull(state.nextRequest())
    }

    @Test fun oldPcIsUnsupportedWithoutAnyExtensionsReady() {
        for (offered in listOf(emptyList(), listOf("message_thoughts_v1"))) {
            val state = active(); state.nextRequest(); state.receive(response())
            state.disconnected(); state.connected("synthetic-registration", ready.copy(capabilities = offered))
            assertEquals(ChatDisplayViewState("unsupported", false), state.view)
            now += 6000; assertNull(state.nextRequest())
            state.receive(response()); assertEquals("unsupported", state.view.status)
        }
    }

    @Test fun wrongNegotiationAndContextNeverApply() {
        val state = ChatDisplayState { now }
        state.connected("synthetic-registration", ready)
        state.receive(ExtensionsReady(2, id(2), id(4), caps)); assertNull(state.nextRequest())
        state.receive(ExtensionsReady(1, id(2), id(4), caps + "character_v1")); assertNull(state.nextRequest())
        state.receive(ExtensionsReady(1, id(2), id(4), caps)); assertNotNull(state.nextRequest())
        for (bad in listOf(response().copy(registration_generation = 2), response().copy(server_epoch = id(8)),
            response().copy(connection_generation = id(8)))) state.receive(bad)
        assertFalse(state.view.splitEnabled)
        state.receive(response()); assertTrue(state.view.splitEnabled)
    }
}
