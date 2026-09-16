package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 실제 TLS HTTP와 가상 장치만 결합한다. PC/Android 두 프로그램의 실기기 왕복은 아니다. */
class AudioTlsSessionTest {
    @Test fun authenticatedPcmReachesPreparedThenOnlyAuthorizedNativeOutput() = runBlocking { scenario(false) }
    @Test fun pauseClosesBlockedTlsReadBeforeAnyDelayedStartCanPlay() = runBlocking { scenario(true) }

    private suspend fun scenario(pauseDuringRead: Boolean) = coroutineScope {
        val ca = TlsTestCertificates.ca()
        val trust = TrustedServer.parse(TlsTestCertificates.SERVER_ID, TlsTestCertificates.encoded(ca.certificate))
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        MockWebServer().use { server ->
            server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            val response = MockResponse.Builder().body(Buffer().write(ByteArray(400)))
                .setHeader("Content-Type", "application/octet-stream").setHeader("Cache-Control", "no-store")
                .setHeader("X-ENE-Audio-Format", "pcm_s16le").setHeader("X-ENE-Sample-Rate", "24000")
                .setHeader("X-ENE-Channels", "1")
            if (pauseDuringRead) response.bodyDelay(1, TimeUnit.SECONDS)
            server.enqueue(response.build())
            TlsClient(trust, Endpoint.parse("127.0.0.1", server.port)).use { tls ->
                val current = AtomicBoolean(true)
                val platform = AudioTestPlatform()
                val sent = mutableListOf<WireMessage>()
                val failures = mutableListOf<String>()
                val ready = Ready(TlsTestCertificates.SERVER_ID, audioId(1), audioId(3), 1, listOf("audio_pcm_v1"))
                val extension = ExtensionSession(ready, this,
                    mediaFactory = { MediaTransport(tls, token, it, current::get) }, platform = platform,
                    send = { sent += it; true }, nowMillis = { System.nanoTime() / 1_000_000 },
                    isPublicAssistant = { it == audioId(4) }, onOutput = {}, onFailure = failures::add,
                )
                try {
                    extension.receive(ExtensionsReady(1, audioId(1), audioId(2), listOf("audio_pcm_v1")))
                    extension.resumed(true); extension.baseState(audioId(1), audioId(3), true)
                    extension.receive(AudioOffer(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 24000, 1, 2, 2000))
                    extension.receive(AudioSourceEnd(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6), 200))
                    val request = withContext(Dispatchers.IO) { requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                    assertEquals("Bearer $token", request.headers["Authorization"])
                    assertEquals(audioId(2), request.headers["X-ENE-Connection"])
                    assertEquals("/companion/v1/audio/${audioId(6)}", request.url.encodedPath)
                    val sink = platform.sinks.single()
                    if (pauseDuringRead) {
                        extension.resumed(false)
                        withTimeout(1000) { while (sink.releases == 0) delay(10) }
                    } else {
                        withTimeout(2500) { while (sent.none { it is AudioPrepared }) delay(10) }
                        assertEquals(200L, sent.filterIsInstance<AudioPrepared>().single().buffered_frames)
                    }
                    assertEquals(0, sink.starts)
                    extension.receive(AudioStart(1, audioId(1), audioId(2), audioId(3), audioId(4), audioId(5), audioId(6)))
                    if (pauseDuringRead) assertEquals(0, sink.starts) else {
                        assertEquals(1, sink.starts)
                        sink.head = 200
                        withTimeout(1000) { while (sent.none { it is AudioFinished }) delay(10) }
                        assertEquals(200L, sent.filterIsInstance<AudioFinished>().single().played_frames)
                    }
                    assertTrue(failures.isEmpty())
                } finally { current.set(false); extension.closeAndJoin() }
                assertEquals(1, platform.sinks.single().releases)
            }
        }
    }
}
