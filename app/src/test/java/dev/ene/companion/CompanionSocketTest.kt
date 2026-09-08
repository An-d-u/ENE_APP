package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.Hello
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

class CompanionSocketTest {
    private val serverId = "00000000-0000-4000-8000-000000000001"
    private val ca = TlsTestCertificates.ca()
    private fun trusted() = TrustedServer.parse(serverId, TlsTestCertificates.encoded(ca.certificate))
    private fun start(server: MockWebServer) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca), ca.certificate).build().sslSocketFactory())
        server.start()
    }
    private fun token() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    @Test fun clientPolicyDisablesRedirectsProxyAndHiddenRetries() {
        val tls = TlsClient(trusted(), Endpoint.parse("127.0.0.1", 8765))
        val client = tls.client
        try {
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(Proxy.NO_PROXY, client.proxy)
            assertEquals(3000, client.connectTimeoutMillis)
        } finally {
            tls.close()
        }
    }

    @Test fun realInfoRequestHasNoAuthorizationAndRedirectIsNotFollowed() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                start(server); start(other)
                OkHttpTransport(trusted()).use { transport ->
                    val endpoint = Endpoint.parse("127.0.0.1", server.port)
                    server.enqueue(MockResponse.Builder().code(200).body("""{"server_id":"$serverId","protocol_versions":[1]}""").build())
                    assertEquals(serverId, transport.info(endpoint).serverId)
                    assertNull(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).headers["Authorization"])
                    server.enqueue(MockResponse.Builder().code(302).addHeader("Location", other.url("/companion/v1/info")).build())
                    val failure = runCatching { transport.info(endpoint) }.exceptionOrNull() as ConnectionException
                    assertEquals("unexpected_server", failure.code)
                    assertEquals(0, other.requestCount)
                }
            }
        }
    }

    @Test fun realSocketUsesHeaderAndReceivesOrderedMessages() = runBlocking {
        MockWebServer().use { server ->
            start(server)
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("첫 가상 프레임"); webSocket.send("다음 가상 프레임") }
                override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send(text) }
            }).build())
            OkHttpTransport(trusted()).use { transport ->
                val secret = token()
                val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), serverId, secret, pairing = false)
                try {
                    assertEquals("Bearer $secret", requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).headers["Authorization"])
                    assertEquals("첫 가상 프레임", withTimeout(2000) { socket.receive() })
                    assertEquals("다음 가상 프레임", withTimeout(2000) { socket.receive() })
                    assertTrue(socket.send(Hello()))
                    assertTrue(withTimeout(2000) { socket.receive() }.contains("hello"))
                } finally { socket.cancel() }
            }
        }
    }

    @Test fun unknown401DoesNotMeanStoredCredentialWasRevoked() = runBlocking {
        MockWebServer().use { server ->
            start(server)
            OkHttpTransport(trusted()).use { transport ->
                val endpoint = Endpoint.parse("127.0.0.1", server.port)
                server.enqueue(MockResponse.Builder().code(401).body("unrecognized gateway").build())
                assertEquals("authorization_unconfirmed", (runCatching { transport.open(endpoint, serverId, token(), false) }.exceptionOrNull() as ConnectionException).code)
                server.enqueue(MockResponse.Builder().code(401).body("""{"code":"unauthorized","server_id":"$serverId"}""").build())
                assertEquals("authorization_revoked", (runCatching { transport.open(endpoint, serverId, token(), false) }.exceptionOrNull() as ConnectionException).code)
            }
        }
    }

    @Test fun inboxLimitsCountBytesAndInvalidatesBufferedDataAfterFailure() = runBlocking {
        var failures = 0
        val inbox = SocketInbox(maxItems = 2, maxBytes = 10, onFailure = { failures++ })
        inbox.offer("별")
        inbox.offer("원")
        assertEquals("별", inbox.receive())
        inbox.offer("별별")
        inbox.offer("별")
        assertEquals(1, failures)
        val failure = runCatching { inbox.receive() }.exceptionOrNull() as ConnectionException
        assertEquals("slow_consumer", failure.code)
        inbox.offer("추가 프레임")
        assertEquals(1, failures)
    }

    @Test fun gracefulClosePreservesAlreadyReceivedFinalApprovalFrame() = runBlocking {
        MockWebServer().use { server ->
            start(server)
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("가상 최종 승인 프레임")
                    webSocket.close(1000, null)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }).build())
            OkHttpTransport(trusted()).use { transport ->
                val socket = transport.open(Endpoint.parse("127.0.0.1", server.port), serverId, null, pairing = true)
                try {
                    delay(100)
                    assertEquals("가상 최종 승인 프레임", withTimeout(2000) { socket.receive() })
                    assertEquals("connection_closed", (runCatching { socket.receive() }.exceptionOrNull() as ConnectionException).code)
                } finally { socket.cancel() }
            }
        }
    }
}
