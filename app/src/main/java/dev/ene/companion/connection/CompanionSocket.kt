package dev.ene.companion.connection

import dev.ene.companion.protocol.ProtocolCodec
import dev.ene.companion.protocol.WireMessage
import dev.ene.companion.protocol.ExtensionContext
import dev.ene.companion.character.CharacterMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.net.ssl.SSLException

interface CompanionSocket {
    suspend fun receive(): String
    fun send(message: WireMessage): Boolean
    fun cancel()
}

interface ConnectionTransport : InfoProbe, Closeable {
    suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket
    val supportsAudio: Boolean get() = false
    fun audio(token: String, context: ExtensionContext, isCurrent: () -> Boolean): AudioMedia =
        throw ConnectionException("audio_unsupported")
    val supportsCharacter: Boolean get() = false
    fun character(token: String, context: ExtensionContext, isCurrent: () -> Boolean): CharacterMedia =
        throw ConnectionException("character_unsupported")
}

/** OkHttp가 callback까지 읽은 뒤의 메모리와 처리 대기열을 제한한다. */
class SocketInbox(
    maxItems: Int = 256,
    private val maxBytes: Int = 4194304,
    private val onFailure: () -> Unit,
) {
    private class Entry(val text: String, val size: Int)
    private val bytes = AtomicInteger()
    private val failure = AtomicReference<String?>()
    private val finished = AtomicBoolean()
    private val channel = Channel<Entry>(maxItems, onUndeliveredElement = { bytes.addAndGet(-it.size) })
    val closed: Boolean get() = failure.get() != null || finished.get()

    fun offer(text: String) {
        if (closed) return
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > ProtocolCodec.MAX_WIRE_BYTES) { fail("message_too_large"); return }
        while (true) {
            val before = bytes.get()
            if (before + size > maxBytes) { fail("slow_consumer"); return }
            if (bytes.compareAndSet(before, before + size)) break
        }
        if (channel.trySend(Entry(text, size)).isFailure) {
            bytes.addAndGet(-size)
            fail("slow_consumer")
        }
    }

    fun fail(code: String) {
        if (!failure.compareAndSet(null, code)) return
        channel.cancel(CancellationException(code))
        onFailure()
    }

    fun finish() {
        // 정상 close 앞에 받은 최종 승인/스냅샷 프레임은 소비할 때까지 유지한다.
        if (finished.compareAndSet(false, true)) channel.close()
    }

    suspend fun receive(): String {
        failure.get()?.let { throw ConnectionException(it) }
        val entry = try { channel.receiveCatching().getOrNull() ?: throw ConnectionException(failure.get() ?: "connection_closed") } catch (error: CancellationException) {
            failure.get()?.let { throw ConnectionException(it) }
            throw error
        }
        bytes.addAndGet(-entry.size)
        failure.get()?.let { throw ConnectionException(it) }
        return entry.text
    }
}

private class OkHttpSocket(private val tls: TlsClient, private val onRelease: (OkHttpSocket) -> Unit) : CompanionSocket {
    private val native = AtomicReference<WebSocket?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watching = AtomicBoolean()
    val inbox = SocketInbox(maxItems = 128, maxBytes = 2_097_152, onFailure = { native.get()?.cancel(); scope.cancel(); onRelease(this) })

    fun attach(socket: WebSocket) {
        native.set(socket)
        if (inbox.closed) socket.cancel()
    }

    private fun validate() {
        try { tls.validate() } catch (error: ConnectionException) { inbox.fail(error.code); throw error }
    }

    fun watchValidity() {
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                try { validate() } catch (_: ConnectionException) { return@launch }
                delay(tls.trust.remainingMillis().coerceIn(1, 60_000))
            }
        }
    }

    fun offer(text: String) {
        try { validate(); inbox.offer(text) } catch (_: ConnectionException) { }
    }

    fun finish() { inbox.finish(); scope.cancel(); onRelease(this) }

    override suspend fun receive(): String {
        validate()
        val text = inbox.receive()
        validate()
        return text
    }
    override fun send(message: WireMessage): Boolean = try {
        validate()
        !inbox.closed && native.get()?.send(ProtocolCodec.encode(message)) == true
    } catch (_: ConnectionException) { false }
    override fun cancel() { inbox.fail("connection_closed") }
}

class OkHttpTransport(private val trust: TrustedServer) : ConnectionTransport, Closeable {
    private var binding: TlsClient? = null
    private var closed = false
    private val sockets = ConcurrentHashMap.newKeySet<OkHttpSocket>()
    override val supportsAudio = true
    override val supportsCharacter = true

    @Synchronized override fun audio(token: String, context: ExtensionContext, isCurrent: () -> Boolean): AudioMedia {
        val tls = binding ?: throw ConnectionException("connection_closed")
        if (closed || sockets.isEmpty() || !isCurrent()) throw ConnectionException("connection_closed")
        return MediaTransport(tls, token, context, isCurrent)
    }

    @Synchronized override fun character(token: String, context: ExtensionContext, isCurrent: () -> Boolean): CharacterMedia {
        val tls = binding ?: throw ConnectionException("connection_closed")
        if (closed || sockets.isEmpty() || !isCurrent()) throw ConnectionException("connection_closed")
        return CharacterMediaTransport(tls, token, context, isCurrent)
    }

    @Synchronized private fun bind(endpoint: Endpoint): TlsClient {
        if (closed) throw ConnectionException("connection_closed")
        trust.validate()
        val current = binding
        if (current != null && current.endpoint != endpoint) {
            sockets.toList().forEach { it.cancel() }
            current.close()
            binding = null
        }
        return binding ?: TlsClient(trust, endpoint).also { binding = it }
    }

    companion object {
        internal fun transportFailure(error: Throwable): String {
            var current: Throwable? = error
            repeat(8) {
                val cause = current ?: return@repeat
                when (cause) {
                    is ConnectionException -> return cause.code
                    is CertificateExpiredException -> return "tls_expired"
                    is CertificateNotYetValidException -> return "tls_clock_invalid"
                    is CertPathValidatorException -> when (cause.reason) {
                        CertPathValidatorException.BasicReason.EXPIRED -> return "tls_expired"
                        CertPathValidatorException.BasicReason.NOT_YET_VALID -> return "tls_clock_invalid"
                        else -> Unit
                    }
                }
                if (cause is SSLException && cause.message in setOf("tls_expired", "tls_clock_invalid", "connection_closed")) return requireNotNull(cause.message)
                current = cause.cause
            }
            return if (error is SSLException) "tls_identity_invalid" else "pc_unreachable"
        }

        private fun boundedBody(response: Response): String {
            val source = response.body.source()
            source.request(8193)
            if (source.buffer.size > 8192) throw ConnectionException("invalid_server_info")
            return source.buffer.readByteArray().decodeToString(throwOnInvalidSequence = true)
        }

        private fun handshakeFailure(response: Response?, expectedServerId: String): String {
            if (response == null) return "pc_unreachable"
            if (response.code != 401) return "unexpected_server"
            return try {
                val body = ProtocolCodec.readObject(boundedBody(response), 8192)
                if (ProtocolCodec.uuid(body["server_id"]) == expectedServerId && ProtocolCodec.text(body["code"]) == "unauthorized") "authorization_revoked"
                else "authorization_unconfirmed"
            } catch (_: Exception) { "authorization_unconfirmed" }
        }
    }

    override suspend fun info(endpoint: Endpoint): ServerInfo = suspendCancellableCoroutine { continuation ->
        val tls = bind(endpoint)
        val call = tls.client.newCall(Request.Builder().url(tls.url("info")).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ConnectionException(transportFailure(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        tls.validate()
                        if (it.code != 200) throw ConnectionException("unexpected_server")
                        ServerInfo.parse(boundedBody(it)).also { info ->
                            tls.validate()
                            if (info.serverId != trust.serverId) throw ConnectionException("server_mismatch")
                        }
                    }
                }
                if (!continuation.isActive) return
                result.fold(continuation::resume, { error ->
                    continuation.resumeWithException(ConnectionException((error as? ConnectionException)?.code ?: "invalid_server_info"))
                })
            }
        })
    }

    override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean): CompanionSocket {
        if (expectedServerId != trust.serverId) throw ConnectionException("server_mismatch")
        val tls = bind(endpoint)
        val request = Request.Builder().url(tls.url(if (pairing) "pair" else "ws"))
        if (!pairing) {
            val credential = try { ProtocolCodec.credential(token?.let(::JsonPrimitive)) }
                catch (_: IllegalArgumentException) { throw ConnectionException("invalid_credentials") }
            request.header("Authorization", "Bearer $credential")
        }
        return suspendCancellableCoroutine { continuation ->
            val socket = OkHttpSocket(tls, onRelease = sockets::remove)
            synchronized(this) {
                if (closed || binding !== tls) { socket.cancel(); throw ConnectionException("connection_closed") }
                sockets.add(socket)
            }
            val opened = AtomicBoolean()
            continuation.invokeOnCancellation { socket.cancel() }
            val native = tls.client.newWebSocket(request.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket.attach(webSocket)
                    if (opened.compareAndSet(false, true)) {
                        try {
                            tls.validate()
                            socket.watchValidity()
                            if (continuation.isActive) continuation.resume(socket, onCancellation = { _, value, _ -> value.cancel() }) else socket.cancel()
                        } catch (error: ConnectionException) {
                            socket.inbox.fail(error.code)
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) { socket.offer(text) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { socket.inbox.fail("invalid_message") }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    socket.finish()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { socket.finish() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = try {
                        tls.validate()
                        if (response == null) transportFailure(t) else handshakeFailure(response, expectedServerId)
                    } catch (error: ConnectionException) { error.code } finally { response?.close() }
                    socket.inbox.fail(code)
                    if (opened.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(ConnectionException(code))
                }
            })
            socket.attach(native)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        sockets.toList().forEach { it.cancel() }
        binding?.close()
        binding = null
    }
}
