package dev.ene.companion

import dev.ene.companion.character.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CharacterPlacementControllerTest {
    private class Store : CharacterPlacementStorage {
        var saved: CharacterPlacement? = null
        var failRead = false
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<CharacterPlacement>()
        override suspend fun load(): CharacterPlacement? {
            if (failRead) error("합성 읽기 실패")
            return saved
        }
        override suspend fun save(value: CharacterPlacement) {
            writes += value
            gate?.await()
            if (failWrite) error("합성 쓰기 실패")
            saved = value
        }
    }

    @Test fun loadsBeforeEditingAndDebouncesLatestValueThenRestores() = runTest {
        val store = Store()
        val controller = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        controller.change(CharacterPlacement(2.0))
        assertFalse(controller.state.value.loaded)
        runCurrent()
        assertEquals(CharacterPlacement(), controller.state.value.placement)
        controller.change(CharacterPlacement(1.2)); advanceTimeBy(200)
        controller.change(CharacterPlacement(1.5, 25.0, 75.0)); advanceTimeBy(249); runCurrent()
        assertTrue(store.writes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(CharacterPlacement(1.5, 25.0, 75.0)), store.writes)
        assertEquals("idle", controller.state.value.saveStatus)
        val restored = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(controller.state.value.placement, restored.state.value.placement)
    }

    @Test fun finishingFlushesAndOldCompletionCannotAcknowledgeNewValue() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val controller = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(CharacterPlacement(1.2)); controller.finishAdjustment(); runCurrent()
        controller.change(CharacterPlacement(1.7)); controller.finishAdjustment(); runCurrent()
        assertEquals(1, store.writes.size)
        assertNotEquals("idle", controller.state.value.saveStatus)
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf(CharacterPlacement(1.2), CharacterPlacement(1.7)), store.writes)
        assertEquals(CharacterPlacement(1.7), store.saved)
        assertEquals("idle", controller.state.value.saveStatus)
    }

    @Test fun failureKeepsLastStoredValueAndRetriesOnlyWhenRequested() = runTest {
        val store = Store().apply { saved = CharacterPlacement(.8); failWrite = true }
        val controller = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(CharacterPlacement(1.6)); controller.finishAdjustment(); runCurrent()
        assertEquals("error", controller.state.value.saveStatus)
        assertEquals(CharacterPlacement(.8), store.saved)
        advanceTimeBy(5000); runCurrent(); assertEquals(1, store.writes.size)
        store.failWrite = false; controller.retrySave(); runCurrent()
        assertEquals(CharacterPlacement(1.6), store.saved)
        assertEquals("idle", controller.state.value.saveStatus)
    }

    @Test fun readFailureDoesNotOverwriteUntilExplicitReset() = runTest {
        val store = Store().apply { failRead = true }
        val controller = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(controller.state.value.readFailed)
        controller.finishAdjustment(); runCurrent(); assertTrue(store.writes.isEmpty())
        controller.reset(); runCurrent()
        assertEquals(CharacterPlacement(), store.saved)
        assertFalse(controller.state.value.readFailed)
    }

    @Test fun newerFailedWriteDoesNotGetRetriedByOldDebounce() = runTest {
        val store = Store().apply { gate = CompletableDeferred(); failWrite = true }
        val controller = CharacterPlacementController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(CharacterPlacement(1.2)); controller.finishAdjustment(); runCurrent()
        controller.change(CharacterPlacement(1.8))
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(2, store.writes.size)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, store.writes.size)
        assertEquals("error", controller.state.value.saveStatus)
    }
}
