package dev.ene.companion

import dev.ene.companion.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatLayoutControllerTest {
    private class Store : ChatLayoutStorage {
        var saved: ChatLayout? = null
        var failRead = false
        var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<ChatLayout>()
        override suspend fun load(): ChatLayout? {
            if (failRead) error("합성 읽기 실패")
            return saved
        }
        override suspend fun save(value: ChatLayout) {
            writes += value
            gate?.await()
            if (failWrite) error("합성 쓰기 실패")
            saved = value
        }
    }

    @Test fun restoreFinishesBeforeEditingAndLatestValueSurvivesRestart() = runTest {
        val store = Store().apply { saved = ChatLayout(.62f) }
        val controller = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        controller.change(.7f)
        assertFalse(controller.state.value.loaded)
        runCurrent()
        assertEquals(.62f, controller.state.value.layout.heightFraction, 0f)
        controller.change(.5f); advanceTimeBy(200)
        controller.change(.7f); advanceTimeBy(249); runCurrent()
        assertTrue(store.writes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(ChatLayout(.7f)), store.writes)
        val restored = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(controller.state.value.layout, restored.state.value.layout)
    }

    @Test fun finishingFlushesAndConcurrentWriteCannotLoseNewerAdjustment() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val controller = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(.6f); controller.finishAdjustment(); runCurrent()
        controller.change(.8f); controller.finishAdjustment(); runCurrent()
        assertEquals(1, store.writes.size)
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf(ChatLayout(.6f), ChatLayout(.8f)), store.writes)
        assertEquals("idle", controller.state.value.saveStatus)
        assertEquals(ChatLayout(.8f), store.saved)
    }

    @Test fun writeFailureKeepsLastCompleteValueAndRequiresExplicitRetry() = runTest {
        val store = Store().apply { saved = ChatLayout(.5f); failWrite = true }
        val controller = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(.7f); controller.finishAdjustment(); runCurrent()
        assertEquals("error", controller.state.value.saveStatus)
        assertEquals(ChatLayout(.5f), store.saved)
        advanceTimeBy(5000); runCurrent(); assertEquals(1, store.writes.size)
        store.failWrite = false; controller.retrySave(); runCurrent()
        assertEquals(ChatLayout(.7f), store.saved)
        assertEquals("idle", controller.state.value.saveStatus)
    }

    @Test fun failedReadIsPreservedUntilUserChangesOrResets() = runTest {
        val store = Store().apply { failRead = true }
        val controller = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(controller.state.value.readFailed)
        controller.finishAdjustment(); runCurrent(); assertTrue(store.writes.isEmpty())
        controller.reset(); runCurrent()
        assertEquals(ChatLayout(), store.saved)
        assertFalse(controller.state.value.readFailed)
    }

    @Test fun pendingDebounceDoesNotRetryFailedLatestWrite() = runTest {
        val store = Store().apply { gate = CompletableDeferred(); failWrite = true }
        val controller = ChatLayoutController(store, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.change(.6f); controller.finishAdjustment(); runCurrent()
        controller.change(.7f)
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(2, store.writes.size)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, store.writes.size)
        assertEquals("error", controller.state.value.saveStatus)
    }
}
