package dev.ene.companion

import dev.ene.companion.character.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import dev.ene.companion.storage.DeviceCredentials

class CharacterDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "1".repeat(64)
    private val bytes = "{\"synthetic\":1}".toByteArray()
    private open inner class Media : CharacterMedia {
        var closed = false
        var downloads = 0
        override suspend fun manifest() = CharacterFixtures.manifest(bytes).toByteArray()
        override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
            downloads++
            onChunk(bytes, bytes.size)
        }
        override fun close() { closed = true }
    }

    @Test fun verifiedCacheStillRequiresFreshManifestButDoesNotDownloadAgain() = runBlocking {
        val repository = CharacterRepository(CharacterCache(temporary.newFolder("cache")))
        val first = Media()
        repository.load(identity, first) { true }.use { assertNotNull(it.character) }
        val second = Media()
        repository.load(identity, second) { true }.use { assertNotNull(it.character) }
        assertEquals(1, first.downloads)
        assertEquals(0, second.downloads)
        assertTrue(first.closed && second.closed)
    }

    @Test fun modelChangeBeforeCommitDoesNotExposeOldDownload() = runBlocking {
        val cache = CharacterCache(temporary.newFolder("cache"))
        val current = AtomicBoolean(true)
        val media = object : Media() {
            override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
                super.asset(asset, onChunk)
                current.set(false)
            }
        }
        assertTrue(runCatching { CharacterRepository(cache).load(identity, media, current::get) }.isFailure)
        assertEquals(0L, cache.usedBytes)
        assertTrue(media.closed)
    }

    @Test fun cancellationClosesMediaAndPartialCache() = runBlocking {
        val cache = CharacterCache(temporary.newFolder("cache"))
        val started = CompletableDeferred<Unit>()
        val media = object : Media() {
            override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
                onChunk(bytes, 3)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { CharacterRepository(cache).load(identity, media) { true }.close() }
        withTimeout(2000) { started.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertEquals(0L, cache.usedBytes)
        assertTrue(media.closed)
    }

    @Test fun downloadIntegrityFailureLeavesChatOutsideItsFailureBoundary() = runBlocking {
        val cache = CharacterCache(temporary.newFolder("cache"))
        val media = object : Media() {
            override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
                onChunk(ByteArray(bytes.size), bytes.size)
            }
        }
        assertTrue(runCatching { CharacterRepository(cache).load(identity, media) { true } }.isFailure)
        assertEquals(0L, cache.usedBytes)
        assertTrue(media.closed)
    }

    @Test fun identitySeparatesRegistrationAndCaWithoutIncludingTokenOrAddress() {
        val ca = TlsTestCertificates.ca()
        val otherCa = TlsTestCertificates.ca()
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        val device = "00000000-0000-4000-8000-000000000001"
        fun credentials(generation: Long, certificate: String, secret: String = token) =
            DeviceCredentials.create(TlsTestCertificates.SERVER_ID, device, generation, secret, certificate)
        val first = CharacterRepository.identity(credentials(1, TlsTestCertificates.encoded(ca.certificate)))
        assertTrue(Regex("[a-f0-9]{64}").matches(first))
        assertNotEquals(first, CharacterRepository.identity(credentials(2, TlsTestCertificates.encoded(ca.certificate))))
        assertNotEquals(first, CharacterRepository.identity(credentials(1, TlsTestCertificates.encoded(otherCa.certificate))))
        val anotherToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
        assertEquals(first, CharacterRepository.identity(credentials(1, TlsTestCertificates.encoded(ca.certificate), anotherToken)))
    }
}
