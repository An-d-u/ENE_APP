package dev.ene.companion

import dev.ene.companion.character.CharacterCache
import dev.ene.companion.character.CharacterSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CharacterCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "1".repeat(64)
    private fun snapshot(bytes: ByteArray) = CharacterSnapshot.parse(CharacterFixtures.manifest(bytes))
    private fun store(cache: CharacterCache, bytes: ByteArray) = cache.begin(identity, snapshot(bytes)).use { ticket ->
        ticket.open(snapshot(bytes).assets.single().id).use { it.write(bytes) }
        ticket.commit()
    }

    @Test fun onlyVerifiedCompleteBundleBecomesVisible() {
        val bytes = "{\"synthetic\":1}".toByteArray()
        val model = snapshot(bytes)
        val cache = CharacterCache(temporary.newFolder("cache"))
        cache.begin(identity, model).use { ticket ->
            assertNull(cache.acquire(identity, model))
            assertThrows(IllegalStateException::class.java) { ticket.commit() }
            ticket.open(model.assets.single().id).use { it.write(bytes) }
            ticket.commit().use { ready ->
                ready.open(model.assets.single().id).use { assertArrayEquals(bytes, it.readBytes()) }
            }
        }
        cache.acquire(identity, model)!!.close()
        assertEquals(1, cache.versionCount)
    }

    @Test fun hashMismatchAndOverrunCannotActivateOrLeaveParts() {
        val bytes = "{\"synthetic\":1}".toByteArray()
        val cache = CharacterCache(temporary.newFolder("cache"))
        for (invalid in listOf(ByteArray(bytes.size), ByteArray(bytes.size + 1))) {
            cache.begin(identity, snapshot(bytes)).use { ticket ->
                assertThrows(IllegalArgumentException::class.java) {
                    ticket.open(snapshot(bytes).assets.single().id).use { it.write(invalid) }
                }
            }
            assertEquals(0, cache.versionCount)
            assertEquals(0L, cache.usedBytes)
        }
    }

    @Test fun stagingReservationsCountAgainstGlobalBudget() {
        val first = ByteArray(2048) { 1 }
        val second = ByteArray(2048) { 2 }
        val cache = CharacterCache(temporary.newFolder("cache"), maxBytes = 4500)
        cache.begin(identity, snapshot(first)).use {
            assertTrue(cache.usedBytes >= first.size)
            assertThrows(IllegalArgumentException::class.java) { cache.begin(identity, snapshot(second)) }
            assertEquals(1, cache.versionCount)
        }
        assertEquals(0L, cache.usedBytes)
    }

    @Test fun pinnedVersionsAndOpenStreamsAreNotEvicted() {
        val cache = CharacterCache(temporary.newFolder("cache"))
        val firstBytes = byteArrayOf(1)
        val first = store(cache, firstBytes)
        val stream = first.open(snapshot(firstBytes).assets.single().id)
        first.close()
        val second = store(cache, byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) { cache.begin(identity, snapshot(byteArrayOf(3))) }
        stream.close()
        store(cache, byteArrayOf(3)).close()
        assertEquals(2, cache.versionCount)
        assertNull(cache.acquire(identity, snapshot(firstBytes)))
        second.close()
    }

    @Test fun anotherRegistrationCannotReuseAnOldIdentityMount() {
        val bytes = "{}".toByteArray()
        val cache = CharacterCache(temporary.newFolder("cache"))
        store(cache, bytes).close()
        assertNull(cache.acquire("2".repeat(64), snapshot(bytes)))
        cache.acquire(identity, snapshot(bytes))!!.close()
    }

    @Test fun reopeningCacheRejectsCorruptedFilesBeforeExposingThem() {
        val root = temporary.newFolder("cache")
        val bytes = "{}".toByteArray()
        val model = snapshot(bytes)
        store(CharacterCache(root), bytes).close()
        File(root, "$identity/${model.modelVersion}/${model.assets.single().id}").writeBytes(byteArrayOf(3, 3))
        val reopened = CharacterCache(root)
        assertNull(reopened.acquire(identity, model))
        assertEquals(0L, reopened.usedBytes)
    }

    @Test fun cancelClosesPartialFilesAndCannotCommitOrOpenUnknownIds() {
        val root = temporary.newFolder("cache")
        val bytes = "{\"synthetic\":1}".toByteArray()
        val cache = CharacterCache(root)
        val ticket = cache.begin(identity, snapshot(bytes))
        val writer = ticket.open(snapshot(bytes).assets.single().id)
        writer.write(bytes, 0, 3)
        assertThrows(IllegalArgumentException::class.java) { ticket.open("../escape") }
        ticket.close()
        assertThrows(IllegalStateException::class.java) { ticket.commit() }
        assertEquals(0, cache.versionCount)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun insufficientStorageFailsBeforeCreatingAStagingVersion() {
        val cache = CharacterCache(temporary.newFolder("cache"), freeBytes = { 0L })
        assertThrows(IllegalArgumentException::class.java) { cache.begin(identity, snapshot("{}".toByteArray())) }
        assertEquals(0, cache.versionCount)
    }

    @Test fun storagePressureReclaimsUnusedVersionBeforeFailing() {
        var available = 4L * 1024 * 1024
        val root = temporary.newFolder("cache")
        val cache = CharacterCache(root, freeBytes = {
            if (root.walkTopDown().any { it.name == "manifest.json" }) available else 4L * 1024 * 1024
        })
        store(cache, byteArrayOf(1)).close()
        available = 0
        store(cache, byteArrayOf(2)).close()
        assertEquals(1, cache.versionCount)
    }
}
