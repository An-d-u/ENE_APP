package dev.ene.companion.connection

import dev.ene.companion.audio.AudioRef
import dev.ene.companion.audio.PcmSlice
import dev.ene.companion.protocol.ExtensionContext
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min

interface AudioMedia : Closeable {
    suspend fun readAudio(ref: AudioRef, sampleRate: Int, channels: Int, onChunk: suspend (PcmSlice) -> Unit): Long
}

/** 주 WSS와 동일한 TLS binding을 빌린다. close는 이 연결의 HTTP만 중단한다. */
internal class MediaTransport(
    private val tls: TlsClient,
    credential: String,
    private val context: ExtensionContext,
    private val isCurrent: () -> Boolean,
) : AudioMedia {
    private val token = ProtocolCodec.credential(JsonPrimitive(credential))
    private var closed = false
    private var active: Call? = null
    private var lastUtteranceId: String? = null
    val activeCount: Int get() = synchronized(this) { if (active == null) 0 else 1 }

    companion object {
        // B6의 native/app 합산 예산에서 반드시 예약한다.
        const val SCRATCH_BYTES = 32768
    }

    private fun validate() {
        if (synchronized(this) { closed } || !isCurrent()) throw ConnectionException("connection_closed")
        tls.validate()
    }

    @Synchronized private fun reserve(ref: AudioRef): Call {
        validate()
        if (!ref.belongsTo(context)) throw ConnectionException("stale_extension")
        if (active != null) throw ConnectionException("resource_busy")
        if (lastUtteranceId == ref.utteranceId) throw ConnectionException("audio_already_consumed")
        val id = try { ProtocolCodec.uuid(JsonPrimitive(ref.utteranceId)) }
            catch (_: IllegalArgumentException) { throw ConnectionException("invalid_audio") }
        val url = tls.url("ws").newBuilder().encodedPath("/companion/v1/audio/$id").build()
        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $token")
            .header("X-ENE-Connection", context.connectionGeneration)
            .header("Accept-Encoding", "identity").build()
        // 기존 CA/이름 검증·redirect 금지·재시도 금지·연결 풀을 그대로 공유한다.
        val call = tls.client.newBuilder().readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(185, TimeUnit.SECONDS).build().newCall(request)
        active = call
        lastUtteranceId = id
        return call
    }

    private fun validateHeaders(response: Response, sampleRate: Int, channels: Int, limit: Long) {
        if (response.code != 200) throw ConnectionException(when (response.code) {
            401 -> "media_unauthorized"
            404 -> "media_not_found"
            410 -> "audio_cancelled"
            429 -> "rate_limited"
            else -> "invalid_media_response"
        })
        fun header(name: String) = response.headers.values(name).singleOrNull()
        if (header("Content-Type") != "application/octet-stream" ||
            header("Cache-Control") != "no-store" ||
            header("X-ENE-Audio-Format") != "pcm_s16le" ||
            header("X-ENE-Sample-Rate") != sampleRate.toString() ||
            header("X-ENE-Channels") != channels.toString() ||
            response.headers.values("Content-Encoding").let { it.isNotEmpty() && it != listOf("identity") } ||
            response.body.contentLength() > limit
        ) throw ConnectionException("invalid_audio")
    }

    /** callback은 구간을 처리한 뒤 반환한다. 같은 scratch가 재사용되므로 참조를 보관하지 않는다. */
    override suspend fun readAudio(
        ref: AudioRef, sampleRate: Int, channels: Int, onChunk: suspend (PcmSlice) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        if (sampleRate !in 8000..48000 || channels !in 1..2) throw ConnectionException("unsupported_format")
        coroutineScope {
            val call = reserve(ref)
            // 취소 시 socket read/execute도 깨운다. 코루틴 취소 플래그만 기다리지 않는다.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (isActive) {
                        validate()
                        delay(min(250L, tls.trust.remainingMillis().coerceAtLeast(1)))
                    }
                } finally { call.cancel() }
            }
            try {
                validate()
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    validate()
                    val frameBytes = channels * 2
                    val limit = min(sampleRate * frameBytes * 180L, 36L * 1024 * 1024)
                    validateHeaders(response, sampleRate, channels, limit)
                    val scratch = ByteArray(SCRATCH_BYTES)
                    var carry = 0
                    var totalBytes = 0L
                    val input = response.body.byteStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        validate()
                        val count = input.read(scratch, carry, scratch.size - carry)
                        validate()
                        if (count < 0) {
                            if (carry != 0) throw ConnectionException("invalid_audio")
                            break
                        }
                        totalBytes += count
                        if (totalBytes > limit) throw ConnectionException("audio_too_large")
                        val available = carry + count
                        val aligned = available - available % frameBytes
                        if (aligned > 0) onChunk(PcmSlice(scratch, 0, aligned))
                        carry = available - aligned
                        if (carry > 0) System.arraycopy(scratch, aligned, scratch, 0, carry)
                    }
                    totalBytes / frameBytes
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                validate()
                throw ConnectionException(OkHttpTransport.transportFailure(error))
            } finally {
                call.cancel()
                watcher.cancel()
                synchronized(this@MediaTransport) { if (active === call) active = null }
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        active?.cancel()
    }
}
