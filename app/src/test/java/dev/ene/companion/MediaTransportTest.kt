package dev.ene.companion

import dev.ene.companion.audio.AudioRef
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.ExtensionContext
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 로컬 TLS 서버와 합성 PCM만 사용한다. 실제 서버 자격증명은 읽지 않는다. */
class MediaTransportTest {
    private fun id(number: Int) = "00000000-0000-4000-8000-" + number.toString().padStart(12, '0')
    private val context = ExtensionContext(1, id(1), id(2), id(3))
    private val ref = AudioRef(1, id(1), id(2), id(3), id(4), id(5), id(6))
    private val credential = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    private fun response(bytes: ByteArray = ByteArray(400)): MockResponse.Builder = MockResponse.Builder()
        .body(Buffer().write(bytes)).setHeader("Content-Type", "application/octet-stream")
        .setHeader("Cache-Control", "no-store").setHeader("X-ENE-Audio-Format", "pcm_s16le")
        .setHeader("X-ENE-Sample-Rate", "24000").setHeader("X-ENE-Channels", "1")

    private suspend fun fixture(block: suspend (MediaTransport, MockWebServer, AtomicBoolean) -> Unit) {
        val ca = TlsTestCertificates.ca()
        val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))
        MockWebServer().use { server ->
            // PC aiohttp와 같은 HTTP/1.1에서 chunked 본문의 실제 프레임 경계를 시험한다.
            server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                val current = AtomicBoolean(true)
                MediaTransport(tls, credential, context, current::get).use { media -> block(media, server, current) }
            }
        }
    }

    @Test fun fixedPathHeadersAndAlignedChunksUseCurrentTrust() = runBlocking {
        fixture { media, server, _ ->
            val bytes = ByteArray(400) { (it % 16).toByte() }
            server.enqueue(response().chunkedBody(Buffer().write(bytes), 3).build())
            val received = java.io.ByteArrayOutputStream()
            val frames = media.readAudio(ref, 24000, 1) {
                assertEquals(0, it.length % 2)
                assertTrue(it.length <= 32768)
                received.write(it.bytes, it.offset, it.length)
            }
            assertEquals(200L, frames)
            assertArrayEquals(bytes, received.toByteArray())
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/companion/v1/audio/" + ref.utteranceId, request.url.encodedPath)
            assertNull(request.url.query)
            assertEquals("Bearer $credential", request.headers["Authorization"])
            assertEquals(context.connectionGeneration, request.headers["X-ENE-Connection"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertTrue(runCatching { media.readAudio(ref, 24000, 1) {} }.exceptionOrNull() is ConnectionException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun staleContextCannotStartOrKeepDownloading() = runBlocking {
        fixture { media, server, current ->
            assertTrue(runCatching { media.readAudio(ref.copy(connectionGeneration = id(99)), 24000, 1) {} }.isFailure)
            assertEquals(0, server.requestCount)
            server.enqueue(response().bodyDelay(1, TimeUnit.SECONDS).build())
            val result = async { runCatching { media.readAudio(ref, 24000, 1) { fail("폐기한 연결의 본문") } } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            current.set(false)
            assertTrue(withTimeout(1000) { result.await() }.isFailure)
        }
    }

    @Test fun mismatchedHeadersCompressionAndTruncatedFrameAreRejected() = runBlocking {
        val invalid = listOf(
            response().setHeader("X-ENE-Sample-Rate", "48000").build(),
            response().setHeader("Content-Encoding", "gzip").build(),
            response().setHeader("Content-Type", "audio/wav").build(),
            response().removeHeader("X-ENE-Channels").build(),
            response(ByteArray(3)).build(),
        )
        for (reply in invalid) fixture { media, server, _ ->
            server.enqueue(reply)
            assertTrue(runCatching { media.readAudio(ref, 24000, 1) {} }.exceptionOrNull() is ConnectionException)
        }
    }

    @Test fun redirectsAreNeverFollowedAndUnauthorizedMediaDoesNotRevokeRegistration() = runBlocking {
        for (status in listOf(302, 401, 404, 410, 429)) fixture { media, server, _ ->
            server.enqueue(MockResponse.Builder().code(status).addHeader("Location", server.url("/elsewhere")).build())
            val error = runCatching { media.readAudio(ref, 24000, 1) {} }.exceptionOrNull() as ConnectionException
            assertNotEquals("authorization_revoked", error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationClosesBlockedBodyAndDoesNotRetry() = runBlocking {
        fixture { media, server, _ ->
            server.enqueue(response().bodyDelay(1, TimeUnit.SECONDS).build())
            val job = launch { media.readAudio(ref, 24000, 1) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertEquals(0, media.activeCount)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun wrongCaCannotReceiveAuthorizationHeader() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val wrong = TlsTestCertificates.ca()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(wrong)).build().sslSocketFactory())
            server.start()
            val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                MediaTransport(tls, credential, context) { true }.use { media ->
                    val failure = runCatching { media.readAudio(ref, 24000, 1) {} }.exceptionOrNull() as ConnectionException
                    assertEquals("tls_identity_invalid", failure.code)
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    @Test fun concurrentConsumerAndOversizedBodyAreRejected() = runBlocking {
        fixture { media, server, _ ->
            server.enqueue(response().bodyDelay(1, TimeUnit.SECONDS).build())
            val first = launch { media.readAudio(ref, 24000, 1) {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            val error = runCatching { media.readAudio(ref.copy(utteranceId = id(99)), 24000, 1) {} }.exceptionOrNull() as ConnectionException
            assertEquals("resource_busy", error.code)
            first.cancelAndJoin()
            assertEquals(1, server.requestCount)
        }
        fixture { media, server, _ ->
            server.enqueue(response().setHeader("Content-Length", 24000 * 2 * 180 + 1).build())
            assertTrue(runCatching { media.readAudio(ref, 24000, 1) { fail("상한 초과 본문") } }.isFailure)
        }
    }

    @Test fun caExpiryCancelsAStalledResponseBeforeDeliveringBytes() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val now = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate), now::get)
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(response().bodyDelay(1, TimeUnit.SECONDS).build())
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                MediaTransport(tls, credential, context) { true }.use { media ->
                    val read = async { runCatching { media.readAudio(ref, 24000, 1) { fail("만료된 본문") } } }
                    withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
                    now.set(ca.certificate.notAfter.time)
                    val error = withTimeout(1000) { read.await() }.exceptionOrNull() as ConnectionException
                    assertEquals("tls_expired", error.code)
                    assertEquals(0, media.activeCount)
                }
            }
        }
    }
}
