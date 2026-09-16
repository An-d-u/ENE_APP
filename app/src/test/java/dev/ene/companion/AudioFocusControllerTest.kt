package dev.ene.companion

import dev.ene.companion.audio.*
import org.junit.Assert.*
import org.junit.Test

class AudioFocusControllerTest {
    private class Port(var grant: String = "granted") : AudioFocusPort {
        var requests = 0
        var abandoned = 0
        var watches = 0
        var unwatches = 0
        lateinit var change: (String) -> Unit
        lateinit var noisy: () -> Unit
        override fun request(onChange: (String) -> Unit): String { requests++; change = onChange; return grant }
        override fun abandon() { abandoned++ }
        override fun watchNoisy(onNoisy: () -> Unit) { watches++; noisy = onNoisy }
        override fun unwatchNoisy() { unwatches++ }
    }

    @Test fun onlyImmediateFocusGrantAllowsPreparation() {
        for (result in listOf("denied", "delayed")) {
            val port = Port(result)
            val controller = AudioFocusController(port) {}
            assertFalse(controller.acquire())
            port.change("gained")
            assertFalse(controller.granted)
            assertFalse(controller.acquire())
            assertEquals(1, port.requests)
            assertEquals(1, port.abandoned)
            controller.close()
        }
    }

    @Test fun lossDuckAndUnplugAreTerminalAndCleanupIsOnce() {
        for (reason in listOf("lost", "transient", "duck", "noisy")) {
            val port = Port()
            val changes = mutableListOf<String>()
            val controller = AudioFocusController(port, changes::add)
            assertTrue(controller.acquire())
            assertTrue(controller.acquire())
            if (reason == "noisy") port.noisy() else port.change(reason)
            port.change("gained")
            port.change("lost")
            assertFalse(controller.granted)
            assertEquals(1, changes.size)
            controller.close()
            controller.close()
            assertEquals(1, port.requests)
            assertEquals(1, port.abandoned)
            assertEquals(1, port.watches)
            assertEquals(1, port.unwatches)
        }
    }

    @Test fun synchronousLossDuringRequestCannotBeOverwrittenByGrant() {
        val changes = mutableListOf<String>()
        var abandoned = 0
        val port = object : AudioFocusPort {
            override fun request(onChange: (String) -> Unit): String {
                onChange("lost")
                return "granted"
            }
            override fun abandon() { abandoned++ }
            override fun watchNoisy(onNoisy: () -> Unit) = Unit
            override fun unwatchNoisy() = Unit
        }
        val controller = AudioFocusController(port, changes::add)
        assertFalse(controller.acquire())
        assertFalse(controller.granted)
        assertEquals(listOf("audio_focus_lost"), changes)
        assertEquals(1, abandoned)
    }

    @Test fun failedReceiverRegistrationIsCleanedWithoutRequestingFocus() {
        var attempts = 0
        var cleaned = 0
        val port = object : AudioFocusPort {
            override fun request(onChange: (String) -> Unit): String { attempts++; return "granted" }
            override fun abandon() = Unit
            override fun watchNoisy(onNoisy: () -> Unit) { throw SecurityException("synthetic") }
            override fun unwatchNoisy() { cleaned++ }
        }
        val controller = AudioFocusController(port) {}
        assertFalse(controller.acquire())
        controller.close()
        assertEquals(0, attempts)
        assertEquals(1, cleaned)
    }
}
