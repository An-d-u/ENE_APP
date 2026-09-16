package dev.ene.companion.connection

import dev.ene.companion.character.CharacterAsset
import dev.ene.companion.character.CharacterMedia
import dev.ene.companion.character.CharacterSnapshot
import dev.ene.companion.protocol.ExtensionContext
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** 음성 전송과 분리된 단일 다운로드. 주 WSS와 동일한 CA·TLS 이름·연결 세대를 검증한다. */
internal class CharacterMediaTransport(
    private val tls: TlsClient,
    credential: String,
    private val context: ExtensionContext,
    private val isCurrent: () -> Boolean,
) : CharacterMedia {
    private val token = ProtocolCodec.credential(JsonPrimitive(credential))
    private var closed = false
    private var active: Call? = null
    val activeCount: Int get() = synchronized(this) { if (active == null) 0 else 1 }

    private fun validate() {
        if (synchronized(this) { closed } || !isCurrent()) throw ConnectionException("connection_closed")
        tls.validate()
    }

    @Synchronized private fun reserve(path: String): Call {
        validate()
        if (active != null) throw ConnectionException("resource_busy")
        val url = tls.url("ws").newBuilder().encodedPath("/companion/v1/character/$path").build()
        val request = Request.Builder().url(url).get().header("Authorization", "Bearer $token")
            .header("X-ENE-Connection", context.connectionGeneration).header("Accept-Encoding", "identity").build()
        return tls.client.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .build().newCall(request).also { active = it }
    }

    override suspend fun manifest(): ByteArray {
        val bytes = ByteArrayOutputStream()
        read("manifest", "application/json", CharacterSnapshot.MAX_MANIFEST_BYTES.toLong(), null) { chunk, count ->
            bytes.write(chunk, 0, count)
        }
        return bytes.toByteArray()
    }

    override suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit) {
        require(Regex("[a-f0-9]{64}").matches(asset.id) && asset.size in 1..CharacterSnapshot.MAX_ASSET_BYTES) { "invalid_asset" }
        read("assets/${asset.id}", asset.mime, asset.size, asset.size, onChunk)
    }

    private suspend fun read(path: String, mime: String, limit: Long, expected: Long?, onChunk: (ByteArray, Int) -> Unit) = withContext(Dispatchers.IO) {
        coroutineScope {
            val call = reserve(path)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (isActive) { validate(); delay(min(250L, tls.trust.remainingMillis().coerceAtLeast(1))) }
                } finally { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    validate()
                    if (response.code != 200) throw ConnectionException(when (response.code) {
                        401 -> "media_unauthorized"
                        404 -> "media_not_found"
                        429 -> "rate_limited"
                        else -> "invalid_media_response"
                    })
                    fun header(name: String) = response.headers.values(name).singleOrNull()
                    val length = response.body.contentLength()
                    if (header("Content-Type") != mime || header("Cache-Control") != "no-store" ||
                        response.headers.values("Content-Encoding").let { it.isNotEmpty() && it != listOf("identity") } ||
                        length > limit || (length >= 0 && expected != null && length != expected)) throw ConnectionException("invalid_character_asset")
                    val scratch = ByteArray(64 * 1024)
                    val input = response.body.byteStream()
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive(); validate()
                        val count = input.read(scratch)
                        currentCoroutineContext().ensureActive(); validate()
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > limit) throw ConnectionException("invalid_character_asset")
                        onChunk(scratch, count)
                    }
                    if (total == 0L || (expected != null && total != expected)) throw ConnectionException("invalid_character_asset")
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive(); validate()
                throw ConnectionException(OkHttpTransport.transportFailure(error))
            } finally {
                call.cancel(); watcher.cancel()
                synchronized(this@CharacterMediaTransport) { if (active === call) active = null }
            }
        }
    }

    @Synchronized override fun close() { closed = true; active?.cancel() }
}
