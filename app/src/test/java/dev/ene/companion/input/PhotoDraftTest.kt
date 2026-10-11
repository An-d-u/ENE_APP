package dev.ene.companion.input

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoDraftTest {
    private class Storage : PhotoStorage {
        val removed = mutableListOf<String>()
        override suspend fun initialize() = Unit
        override suspend fun prepare(source: String): LocalPhoto {
            if (source == "invalid") error("synthetic_failure")
            return LocalPhoto(source, "image/png", "a".repeat(64), 32, 16, 12, byteArrayOf())
        }
        override suspend fun read(photo: LocalPhoto) = ByteArray(32)
        override suspend fun delete(photos: List<LocalPhoto>) { removed += photos.map { it.id } }
    }
    @Test fun partialSelectionFailurePreservesPreviousDraftAndReclaimsCandidates() = runTest {
        val storage = Storage()
        val draft = PhotoDraft(storage, backgroundScope)
        runCurrent()
        draft.select(listOf("first")); runCurrent()
        draft.select(listOf("second", "invalid")); runCurrent()
        assertEquals(listOf("first"), draft.state.value.images.map { it.id })
        assertEquals(listOf("second"), storage.removed)
        assertNotNull(draft.state.value.notice)
    }
    @Test fun fourImageLimitAndAcceptanceOnlyRemoveSubmittedPhotos() = runTest {
        val storage = Storage()
        val draft = PhotoDraft(storage, backgroundScope)
        runCurrent()
        draft.select(listOf("one", "two", "three", "four")); runCurrent()
        draft.select(listOf("five")); runCurrent()
        assertEquals(4, draft.state.value.images.size)
        draft.accepted(setOf("one", "two")); runCurrent()
        assertEquals(listOf("three", "four"), draft.state.value.images.map { it.id })
        assertEquals(listOf("one", "two"), storage.removed)
    }
    @Test fun registrationResetDiscardsLatePreparedFile() = runTest {
        val ready = CompletableDeferred<Unit>()
        val removed = mutableListOf<String>()
        val storage = object : PhotoStorage {
            override suspend fun initialize() = Unit
            override suspend fun prepare(source: String): LocalPhoto = withContext(NonCancellable) {
                ready.await(); LocalPhoto("late", "image/png", "a".repeat(64), 32, 16, 12, byteArrayOf())
            }
            override suspend fun read(photo: LocalPhoto) = ByteArray(32)
            override suspend fun delete(photos: List<LocalPhoto>) { removed += photos.map { it.id } }
        }
        val draft = PhotoDraft(storage, backgroundScope)
        runCurrent(); draft.select(listOf("selection")); runCurrent()
        draft.reset(); ready.complete(Unit); runCurrent()
        assertTrue(draft.state.value.images.isEmpty())
        assertEquals(listOf("late"), removed)
    }
}
