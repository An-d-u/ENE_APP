package dev.ene.companion

import dev.ene.companion.character.CharacterSnapshot
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.ExtensionContext
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CharacterTransportTest {
    private val context = ExtensionContext(1, "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
    private val credential = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private val bytes = "{}".toByteArray()
    private val snapshot = CharacterSnapshot.parse(CharacterFixtures.manifest(bytes))
    private fun response(body: String) = MockResponse.Builder().body(body)
        .setHeader("Content-Type", "application/json").setHeader("Cache-Control", "no-store")

    private suspend fun fixture(block: suspend (CharacterMediaTransport, MockWebServer, AtomicBoolean) -> Unit) {
        val ca = TlsTestCertificates.ca()
        val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                val current = AtomicBoolean(true)
                CharacterMediaTransport(tls, credential, context, current::get).use { media -> block(media, server, current) }
            }
        }
    }

    @Test fun fixedAuthenticatedPathsNeverContainCredentialsOrQuery() = runBlocking {
        fixture { media, server, _ ->
            val raw = CharacterFixtures.manifest(bytes)
            server.enqueue(response(raw).build())
            assertArrayEquals(raw.toByteArray(), media.manifest())
            server.enqueue(response("{}").build())
            val received = java.io.ByteArrayOutputStream()
            media.asset(snapshot.assets.single()) { chunk, count -> received.write(chunk, 0, count) }
            assertArrayEquals(bytes, received.toByteArray())
            for (path in listOf("manifest", "assets/${snapshot.assets.single().id}")) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/companion/v1/character/$path", request.url.encodedPath)
                assertNull(request.url.query)
                assertNull(request.headers["Origin"])
                assertNull(request.headers["Range"])
                assertEquals("Bearer $credential", request.headers["Authorization"])
                assertEquals(context.connectionGeneration, request.headers["X-ENE-Connection"])
                assertEquals("identity", request.headers["Accept-Encoding"])
            }
        }
    }

    @Test fun wrongHeadersOversizedManifestAndRedirectAreRejected() = runBlocking {
        val replies = listOf(response("{}").setHeader("Content-Encoding", "gzip").build(),
            response("{}").setHeader("Cache-Control", "public").build(),
            response("{}").setHeader("Content-Type", "text/html").build(),
            response("{}").setHeader("Content-Length", CharacterSnapshot.MAX_MANIFEST_BYTES + 1).build(),
            response("{}").code(302).setHeader("Location", "https://example.invalid/").build())
        for (reply in replies) fixture { media, server, _ ->
            server.enqueue(reply)
            assertTrue(runCatching { media.manifest() }.exceptionOrNull() is ConnectionException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun staleConnectionCancelsBodyAndCannotStartNextRequest() = runBlocking {
        fixture { media, server, current ->
            server.enqueue(response("{}").bodyDelay(2, TimeUnit.SECONDS).build())
            val result = async { runCatching { media.manifest() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            current.set(false)
            assertTrue(withTimeout(1000) { result.await() }.isFailure)
            assertTrue(runCatching { media.manifest() }.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals(0, media.activeCount)
        }
    }

    @Test fun cancellationClosesBlockedSocketAndConcurrentAssetIsBounded() = runBlocking {
        fixture { media, server, _ ->
            server.enqueue(response("{}").bodyDelay(2, TimeUnit.SECONDS).build())
            val job = launch { media.asset(snapshot.assets.single()) { _, _ -> fail("취소한 본문") } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            assertTrue(runCatching { media.asset(snapshot.assets.single()) { _, _ -> } }.isFailure)
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(0, media.activeCount)
        }
    }
}
