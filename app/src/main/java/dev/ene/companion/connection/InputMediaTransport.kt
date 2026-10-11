package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface InputMedia : Closeable {
    suspend fun upload(id: String, bytes: ByteArray)
    suspend fun thumbnail(message: String, image: String, revision: Long): ByteArray
}

/** 기존 인증서·호스트 검증과 재시도/리디렉션 금지를 유지하는 입력 전용 HTTP 경로. */
internal class InputMediaTransport(private val tls: TlsClient, credential: String,
    private val context: ExtensionContext, private val current: () -> Boolean) : InputMedia {
    private val token = ProtocolCodec.credential(JsonPrimitive(credential))
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private fun validate() {
        if (closed || !current()) throw ConnectionException("connection_closed")
        tls.validate()
    }
    private fun id(value: String) = ProtocolCodec.uuid(JsonPrimitive(value))
    private suspend fun request(path: String, bytes: ByteArray?, revision: Long?): ByteArray = withContext(Dispatchers.IO) {
        coroutineScope {
            validate()
            val request = Request.Builder().url(tls.url("ws").newBuilder().encodedPath(path).build())
                .header("Authorization", "Bearer $token").header("X-ENE-Connection", context.connectionGeneration)
                .header("Accept-Encoding", "identity")
            if (bytes != null) request.put(bytes.toRequestBody("application/octet-stream".toMediaType())) else request.get()
            val call = tls.client.newBuilder().readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS).build().newCall(request.build())
            calls.add(call)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { while (isActive) { validate(); delay(200) } } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    validate()
                    if (response.code != 200 || response.header("Cache-Control") != "no-store" ||
                        response.header("Content-Encoding")?.let { it != "identity" } == true) throw ConnectionException("input_transfer_failed")
                    if (revision != null && (response.header("X-ENE-Attachment-Revision") != revision.toString() ||
                        response.header("Content-Type") !in setOf("image/jpeg", "image/png"))) throw ConnectionException("stale_image")
                    val limit = if (bytes == null) 131072L else 1024L
                    val source = response.body.source()
                    source.request(limit + 1)
                    if (source.buffer.size > limit) throw ConnectionException("input_too_large")
                    validate(); currentCoroutineContext().ensureActive()
                    source.buffer.readByteArray().also { body ->
                        if (bytes != null && (response.header("Content-Type")?.substringBefore(';') != "application/json" ||
                            ProtocolCodec.readObject(body.decodeToString(throwOnInvalidSequence = true), 1024)["status"] != JsonPrimitive("ready")))
                            throw ConnectionException("input_transfer_failed")
                    }
                }
            } finally { watcher.cancel(); call.cancel(); calls.remove(call) }
        }
    }
    override suspend fun upload(id: String, bytes: ByteArray) {
        require(bytes.size in 1..4194304)
        request("/companion/v1/input/${id(id)}", bytes, null)
    }
    override suspend fun thumbnail(message: String, image: String, revision: Long): ByteArray =
        request("/companion/v1/chat-images/${id(message)}/${id(image)}", null, revision)
    override fun close() { closed = true; calls.forEach { it.cancel() } }
}
