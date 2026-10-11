package dev.ene.companion.input

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackgroundControllerTest {
    class Store : BackgroundStorage {
        var value = BackgroundSettings()
        var fail = false
        val removed = mutableListOf<String>()
        override suspend fun load() = value
        override suspend fun save(settings: BackgroundSettings) { if (fail) error("저장 실패"); value = settings }
        override suspend fun prepare(source: String) = "00000000-0000-4000-8000-000000000001"
        override suspend fun image(id: String) = byteArrayOf(1)
        override suspend fun cleanup(keep: Set<String>) { if (keep.isEmpty()) removed += "candidate" }
    }
    @Test fun failedPhotoReplacementKeepsLastGoodBackground() = runTest {
        val storage = Store()
        val controller = BackgroundController(storage, backgroundScope)
        runCurrent()
        storage.fail = true
        controller.select("content://synthetic/photo")
        runCurrent()
        assertEquals("default", controller.state.value.settings.mode)
        assertEquals("error", controller.state.value.status)
        assertEquals(BackgroundSettings(), storage.value)
    }
    @Test fun latestAdjustmentIsSavedAndOnlyBackgroundStateChanges() = runTest {
        val storage = Store()
        val controller = BackgroundController(storage, backgroundScope)
        runCurrent()
        controller.change(BackgroundSettings(mode = "solid", dim = .5f, blur = 8))
        controller.change(BackgroundSettings(mode = "solid", dim = .7f, blur = 16))
        controller.finish()
        runCurrent()
        assertEquals(.7f, storage.value.dim)
        assertEquals(16, storage.value.blur)
    }
}
