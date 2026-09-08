package dev.ene.companion

import dev.ene.companion.connection.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class TlsClientTest {
    private fun trust(ca: okhttp3.tls.HeldCertificate) = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))

    @Test fun webSocketDoesNotReuseInfoIdleConnectionToSkipFreshCaCheck() {
        val ca = TlsTestCertificates.ca()
        val leaf = TlsTestCertificates.leaf(ca)
        MockWebServer().use { server ->
            server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().body("가상 응답").build())
            val time = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val lookups = java.util.concurrent.atomic.AtomicInteger()
            val trusted = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate), time::get)
            TlsClient(trusted, Endpoint.parse("synthetic-pc.test", server.port), routeLookup = {
                if (lookups.incrementAndGet() > 1) time.set(ca.certificate.notAfter.time)
                listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            }).use { tls ->
                tls.client.newCall(Request.Builder().url(tls.url("info")).build()).execute().use { assertEquals(200, it.code); it.body.string() }
                assertEquals(0, tls.client.connectionPool.idleConnectionCount())
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNull(request.headers["Authorization"])
                val finished = CountDownLatch(1)
                val socket = tls.client.newWebSocket(Request.Builder().url(tls.url("ws")).header("Authorization", "Bearer synthetic-test-only").build(), object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); finished.countDown() }
                })
                try { assertTrue(finished.await(5, TimeUnit.SECONDS)) } finally { socket.cancel() }
                assertEquals(2, lookups.get())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun caExpiryDuringConnectionPreventsHttpAndWebSocketTokenHeaders() {
        val ca = TlsTestCertificates.ca()
        val leaf = TlsTestCertificates.leaf(ca)
        for (webSocket in listOf(false, true)) {
            MockWebServer().use { server ->
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory())
                server.start()
                server.enqueue(MockResponse.Builder().body("가상 응답").build())
                val time = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                val trusted = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate), time::get)
                TlsClient(trusted, Endpoint.parse("synthetic-pc.test", server.port), routeLookup = {
                    time.set(ca.certificate.notAfter.time)
                    listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }).use { tls ->
                    val request = Request.Builder().url(tls.url(if (webSocket) "ws" else "info"))
                        .header("Authorization", "Bearer synthetic-test-only").build()
                    if (webSocket) {
                        val finished = CountDownLatch(1)
                        val socket = tls.client.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) { finished.countDown(); webSocket.cancel() }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { response?.close(); finished.countDown() }
                        })
                        try { assertTrue(finished.await(5, TimeUnit.SECONDS)) } finally { socket.cancel() }
                    } else {
                        try { tls.client.newCall(request).execute().close() } catch (_: java.io.IOException) { }
                    }
                    assertEquals("협상 중 만료되면 토큰을 담은 HTTP 요청도 없어야 함", 0, server.requestCount)
                }
            }
        }
    }

    @Test fun dedicatedClientUsesFixedHostnameAndOnlySelectedCa() {
        val ca = TlsTestCertificates.ca()
        val leaf = TlsTestCertificates.leaf(ca)
        MockWebServer().use { server ->
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, ca.certificate).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().body("가상 TLS 응답").build())
            val trusted = trust(ca)
            TlsClient(trusted, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                assertEquals(trusted.hostname, tls.url("info").host)
                assertEquals("https", tls.url("info").scheme)
                assertEquals(Proxy.NO_PROXY, tls.client.proxy)
                assertFalse(tls.client.followRedirects)
                assertFalse(tls.client.followSslRedirects)
                assertFalse(tls.client.retryOnConnectionFailure)
                assertTrue(tls.client.connectionSpecs.all { it.isTls })
                assertEquals(listOf(trusted.certificate), tls.trustManager.acceptedIssuers.toList())
                tls.client.newCall(Request.Builder().url(tls.url("info")).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals(okhttp3.Protocol.HTTP_2, it.protocol)
                }
                val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                // 시험 서버의 HTTP/2는 Host 헤더 대신 요청 authority를 URL로 보존한다.
                assertEquals(trusted.hostname, request.url.host)
                assertNull(request.headers["Authorization"])
            }
        }
    }

    @Test fun wrongCaWrongNameAndInvalidDatesFailBeforeAnyHttpRequest() {
        val ca = TlsTestCertificates.ca()
        val other = TlsTestCertificates.ca()
        val now = System.currentTimeMillis()
        val leaves = listOf(TlsTestCertificates.leaf(other), TlsTestCertificates.leaf(ca, hostname = "wrong.invalid"),
            TlsTestCertificates.leaf(ca, before = now - 172_800_000, after = now - 86_400_000),
            TlsTestCertificates.leaf(ca, before = now + 86_400_000, after = now + 172_800_000))
        for (leaf in leaves) {
            MockWebServer().use { server ->
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory())
                server.start()
                TlsClient(trust(ca), Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                    assertThrows(SSLException::class.java) {
                        tls.client.newCall(Request.Builder().url(tls.url("info")).header("Authorization", "Bearer synthetic-test-only").build()).execute().close()
                    }
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    @Test fun fixedTlsNameIsNeverPassedToSystemDnsAndUnrelatedNamesAreRejected() {
        val ca = TlsTestCertificates.ca()
        val lookedUp = mutableListOf<String>()
        TlsClient(trust(ca), Endpoint.parse("pc.192.0.2.10", 8765), routeLookup = { name ->
            lookedUp.add(name)
            listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }).use { tls ->
            assertEquals("127.0.0.1", tls.client.dns.lookup(tls.url("info").host).single().hostAddress)
            assertEquals(listOf("pc.192.0.2.10"), lookedUp)
            assertThrows(UnknownHostException::class.java) { tls.client.dns.lookup("unrelated.test") }
            assertEquals(1, lookedUp.size)
        }
    }

    @Test fun timeoutDoesNotCreateMoreNativeDnsWorkersWhileOldLookupIsStillRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        BoundedDnsLookup(timeoutMillis = 30, systemLookup = {
            calls.incrementAndGet()
            entered.countDown()
            try {
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { /* 취소를 무시하는 OS 조회를 재현한다. */ }
                }
                arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            } finally { exited.countDown() }
        }).use { resolver ->
            try {
                assertThrows(UnknownHostException::class.java) { resolver.lookup("synthetic-pc.test") }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                repeat(4) { assertThrows(UnknownHostException::class.java) { resolver.lookup("second-pc.test") } }
                assertEquals(1L, exited.count)
                assertEquals(1, calls.get())
            } finally {
                release.countDown()
                assertTrue(exited.await(1, TimeUnit.SECONDS))
            }
        }
    }
}
