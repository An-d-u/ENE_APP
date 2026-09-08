package dev.ene.companion.connection

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 연결 주소마다 독립 풀을 만들며 URL/SNI/호스트 검증에는 고정 TLS 이름만 쓴다. */
internal class TlsClient(
    val trust: TrustedServer,
    val endpoint: Endpoint,
    routeLookup: (String) -> List<InetAddress> = BoundedDnsLookup.shared::lookup,
) : Closeable {
    private val closed = AtomicBoolean()
    val trustManager: X509TrustManager
    val client: OkHttpClient

    init {
        trust.validate()
        if (Endpoint.parse(endpoint.host, endpoint.port) != endpoint) throw ConnectionException("invalid_endpoint")
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setCertificateEntry("ene-ca", trust.certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        trustManager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        val dns = Dns { name ->
            if (name != trust.hostname || closed.get()) throw UnknownHostException("unexpected_server")
            val numeric = endpoint.host.split('.').map { it.toIntOrNull() }
            val addresses = if (numeric.size == 4 && numeric.all { it != null && it in 0..255 }) {
                listOf(InetAddress.getByAddress(numeric.map { requireNotNull(it).toByte() }.toByteArray()))
            } else routeLookup(endpoint.host)
            addresses.filterIsInstance<Inet4Address>().distinct().take(8)
                .ifEmpty { throw UnknownHostException("unsupported_address_family") }
        }
        val builder = OkHttpClient.Builder()
        val defaultHostnameVerifier = builder.build().hostnameVerifier
        client = builder
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { hostname, session ->
                // 기본 이름 검증을 완전히 유지하며 CA 날짜 검사만 추가한다.
                // WS가 EventListener를 끄므로 새 TLS 연결의 공통 검증 지점이 필요하다.
                try {
                    validate()
                    defaultHostnameVerifier.verify(hostname, session).also { validate() }
                } catch (_: ConnectionException) { false }
            }
            // WS가 /info 유휴 연결을 재사용하며 이름/날짜 검사를 생략하지 못하게 한다.
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
            .dns(dns)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) {
                    // HTTP/2의 진행 중 연결을 공유하는 HTTPS 요청도 현재 CA를 확인한다.
                    try { validate() } catch (_: ConnectionException) { call.cancel() }
                }
            })
            .addInterceptor { chain ->
                try { validate() } catch (error: ConnectionException) { throw SSLHandshakeException(error.code) }
                try { chain.proceed(chain.request()) } catch (error: IOException) {
                    try { validate() } catch (invalid: ConnectionException) { throw SSLHandshakeException(invalid.code) }
                    throw error
                }
            }
            .build()
    }

    fun validate() {
        if (closed.get()) throw ConnectionException("connection_closed")
        trust.validate()
    }

    fun url(path: String): HttpUrl {
        validate()
        if (path !in setOf("info", "pair", "ws")) throw ConnectionException("unexpected_server")
        return HttpUrl.Builder().scheme("https").host(trust.hostname).port(endpoint.port)
            .encodedPath("/companion/v1/$path").build()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
