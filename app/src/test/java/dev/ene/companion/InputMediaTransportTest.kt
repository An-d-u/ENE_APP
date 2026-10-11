package dev.ene.companion

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

class InputMediaTransportTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private val context = ExtensionContext(1, id(1), id(2), id(3))
    private val credential = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private suspend fun fixture(block: suspend (InputMediaTransport, MockWebServer, AtomicBoolean) -> Unit) {
        val ca = TlsTestCertificates.ca()
        val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                val current = AtomicBoolean(true)
                InputMediaTransport(tls, credential, context, current::get).use { block(it, server, current) }
            }
        }
    }
    @Test fun fixedLengthPutUsesRegisteredTlsAndNoQuery() = runBlocking {
        fixture { media, server, _ ->
            server.enqueue(MockResponse.Builder().body("{\"status\":\"ready\"}").setHeader("Cache-Control", "no-store").setHeader("Content-Type", "application/json").build())
            media.upload(id(4), ByteArray(80))
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("PUT", request.method)
            assertEquals("/companion/v1/input/${id(4)}", request.url.encodedPath)
            assertNull(request.url.query)
            assertEquals("80", request.headers["Content-Length"])
            assertNull(request.headers["Transfer-Encoding"])
            assertEquals(context.connectionGeneration, request.headers["X-ENE-Connection"])
            assertEquals("Bearer $credential", request.headers["Authorization"])
        }
    }
    @Test fun thumbnailRevisionMismatchRedirectAndOversizeAreRejected() = runBlocking {
        val responses = listOf(
            MockResponse.Builder().code(302).setHeader("Location", "/another").build(),
            MockResponse.Builder().body("synthetic").setHeader("Cache-Control", "no-store").setHeader("Content-Type", "image/png").setHeader("X-ENE-Attachment-Revision", "2").build(),
            MockResponse.Builder().body("x".repeat(131073)).setHeader("Cache-Control", "no-store").setHeader("Content-Type", "image/png").setHeader("X-ENE-Attachment-Revision", "1").build())
        for (response in responses) fixture { media, server, _ ->
            server.enqueue(response)
            assertTrue(runCatching { media.thumbnail(id(4), id(5), 1) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun disconnectAbortsBlockedResponseWithoutRetry() = runBlocking {
        fixture { media, server, current ->
            server.enqueue(MockResponse.Builder().body("{}").bodyDelay(2, TimeUnit.SECONDS).setHeader("Cache-Control", "no-store").build())
            val job = async { runCatching { media.upload(id(4), ByteArray(80)) } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            current.set(false)
            assertTrue(withTimeout(1000) { job.await() }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
}
