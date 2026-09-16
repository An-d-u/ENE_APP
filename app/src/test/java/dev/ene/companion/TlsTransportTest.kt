package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.Hello
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test

class TlsTransportTest {
    private val id = TlsTestCertificates.SERVER_ID
    private fun credentials() = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    @Test fun socketRemovedBetweenSizeAndIterationCannotAbortTransportCleanup() {
        val ca = TlsTestCertificates.ca()
        val transport = OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate)))
        // 만료 콜백이 size 확인 직후 마지막 항목을 지운 약한 일관성 집합을 재현한다.
        val disappearing = object : AbstractMutableSet<Any>() {
            override val size: Int get() = 1
            override fun iterator(): MutableIterator<Any> = mutableListOf<Any>().iterator()
            override fun add(element: Any) = error("시험 중 등록 없음")
        }
        transport.javaClass.getDeclaredField("sockets").apply { isAccessible = true }.set(transport, disappearing)
        transport.close(); transport.close()
    }

    @Test fun untrustedServerCannotReceiveTokenOrRevokeRegistration() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val wrong = TlsTestCertificates.ca()
        val trusted = TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(wrong)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized","server_id":"$id"}""").build())
            OkHttpTransport(trusted).use { transport ->
                val error = runCatching { transport.open(Endpoint.parse("127.0.0.1", server.port), id, credentials(), false) }.exceptionOrNull() as ConnectionException
                assertEquals("tls_identity_invalid", error.code)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun differentExpectedIdIsRejectedBeforeTraffic() = runBlocking {
        val ca = TlsTestCertificates.ca()
        MockWebServer().use { server ->
            server.start()
            OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))).use { transport ->
                val failure = runCatching { transport.open(Endpoint.parse("127.0.0.1", server.port), "00000000-0000-4000-8000-000000000002", credentials(), false) }.exceptionOrNull() as ConnectionException
                assertEquals("server_mismatch", failure.code)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun activeSocketStopsAcceptingMessagesWhenCaExpires() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val clock = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val trusted = TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate), clock = clock::get)
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("가상 수신 프레임") }
            }).build())
            OkHttpTransport(trusted).use { transport ->
                val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), id, null, true)
                try {
                    assertEquals("가상 수신 프레임", withTimeout(2000) { socket.receive() })
                    clock.set(ca.certificate.notAfter.time)
                    assertFalse(socket.send(Hello()))
                    assertEquals("tls_expired", (runCatching { socket.receive() }.exceptionOrNull() as ConnectionException).code)
                } finally { socket.cancel() }
            }
        }
    }

    @Test fun closingTransportCancelsActiveWebsocket() = runBlocking {
        val ca = TlsTestCertificates.ca()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
            val transport = OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate)))
            val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), id, null, true)
            transport.close()
            assertEquals("connection_closed", (runCatching { withTimeout(2000) { socket.receive() } }.exceptionOrNull() as ConnectionException).code)
        }
    }

    @Test fun closingTransportAlsoInvalidatesBufferedFinalFrameFromFinishedSocket() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val ended = java.util.concurrent.CountDownLatch(1)
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("합성 최종 프레임"); webSocket.close(1000, null) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.countDown() }
            }).build())
            OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))).use { transport ->
                val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), id, null, true)
                withTimeout(2000) { while (ended.count > 0) delay(1) }
                // peer의 종료만으로는 callback 완료를 보장하지 않아 클라이언트 등록 해제도 기다린다.
                val registered = transport.javaClass.getDeclaredField("sockets").apply { isAccessible = true }
                withTimeout(2000) { while ((registered.get(transport) as Set<*>).isNotEmpty()) delay(1) }
                transport.close()
                assertEquals("connection_closed", (runCatching { socket.receive() }.exceptionOrNull() as ConnectionException).code)
            }
        }
    }

    @Test fun idleWebsocketIsClosedAtCaDeadlineWithoutAReadOrSendTrigger() = runBlocking {
        val ca = TlsTestCertificates.ca()
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
            val started = System.nanoTime()
            val trust = TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate), clock = { ca.certificate.notAfter.time - 4000 + (System.nanoTime() - started) / 1_000_000 })
            OkHttpTransport(trust).use { transport ->
                val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), id, null, true)
                val failure = runCatching { withTimeout(6000) { socket.receive() } }.exceptionOrNull() as ConnectionException
                assertEquals("tls_expired", failure.code)
            }
        }
    }

    @Test fun changingEndpointCancelsOldSocketAndDoesNotSendTokenToInfo() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val ssl = HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory()
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.useHttps(ssl); second.useHttps(ssl)
                first.start(); second.start()
                first.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
                second.enqueue(MockResponse.Builder().body("""{"server_id":"$id","protocol_versions":[1]}""").build())
                OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))).use { transport ->
                    val old = transport.open(Endpoint.parse("127.0.0.1", first.port), id, credentials(), false)
                    assertEquals(id, transport.info(Endpoint.parse("127.0.0.1", second.port)).serverId)
                    assertEquals("connection_closed", (runCatching { old.receive() }.exceptionOrNull() as ConnectionException).code)
                    assertNull(requireNotNull(second.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)).headers["Authorization"])
                }
            }
        }
    }

    @Test fun trustedInfoDoesNotAllowTokenAfterServerCertificateIsReplaced() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val wrong = TlsTestCertificates.ca()
        MockWebServer().use { first ->
            first.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            first.start()
            val endpoint = Endpoint.parse("127.0.0.1", first.port)
            first.enqueue(MockResponse.Builder().body("""{"server_id":"$id","protocol_versions":[1]}""").build())
            OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))).use { transport ->
                transport.info(endpoint)
                first.close()
                MockWebServer().use { replacement ->
                    replacement.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(wrong)).build().sslSocketFactory())
                    replacement.start(endpoint.port)
                    val failure = runCatching { transport.open(endpoint, id, credentials(), false) }.exceptionOrNull() as ConnectionException
                    assertEquals("tls_identity_invalid", failure.code)
                    assertEquals(0, replacement.requestCount)
                }
            }
        }
    }

    @Test fun plaintextServerIsNeverUsedAsFallback() = runBlocking {
        val ca = TlsTestCertificates.ca()
        MockWebServer().use { server ->
            server.start()
            OkHttpTransport(TrustedServer.parse(id, TlsTestCertificates.encoded(ca.certificate))).use { transport ->
                val failure = runCatching { transport.open(Endpoint.parse("127.0.0.1", server.port), id, credentials(), false) }.exceptionOrNull()
                assertTrue(failure is ConnectionException)
                assertNotEquals("authorization_revoked", (failure as ConnectionException).code)
                assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS)?.headers?.get("Authorization"))
            }
        }
    }
}
