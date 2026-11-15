package dev.ene.companion.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 질문은 접수 뒤의 한 프레임만 받고 자동 관찰은 작은 색상 표본만 비교한다. */
internal class CameraFrameSampler {
    private var question: CompletableDeferred<ByteArray?>? = null
    @Volatile private var closed = false
    // 분석 콜백은 단일 작업 스레드에서 호출된다.
    private var previous: IntArray? = null
    private var lastEpoch: Long? = null
    private var nextSample = 0L
    private var nextUpload = 0L

    suspend fun nextJpeg(): ByteArray? {
        val request = synchronized(this) {
            if (closed || question != null) return null
            CompletableDeferred<ByteArray?>().also { question = it }
        }
        return try {
            withTimeoutOrNull(1000) { request.await() }
        } finally {
            synchronized(this) { if (question === request) question = null }
        }
    }

    fun frame(now: Long, observationEpoch: () -> Long?, signature: () -> IntArray,
              encode: () -> ByteArray?, observe: (Long, ByteArray) -> Unit) {
        if (closed) return
        val request = synchronized(this) { question }
        val epoch = observationEpoch()
        if (epoch != lastEpoch) {
            lastEpoch = epoch
            previous = null
            nextSample = 0
        }
        val candidate = epoch != null && now >= nextSample && now >= nextUpload && changed(now, signature())
        if (request == null && !candidate) return
        val jpeg = encode()
        synchronized(this) {
            if (closed) return
            if (request != null && request === question) {
                question = null
                request.complete(jpeg)
            }
        }
        if (candidate && epoch != null && jpeg != null && !closed && observationEpoch() == epoch) {
            nextUpload = now + 60_000
            observe(epoch, jpeg)
        }
    }

    private fun changed(now: Long, sample: IntArray): Boolean {
        nextSample = now + 15_000
        val old = previous
        previous = sample
        if (old == null || old.size != sample.size) return false
        val changed = sample.indices.count { abs(old[it] - sample[it]) >= 24 }
        return changed * 100 >= sample.size * 8
    }

    @Synchronized fun close() {
        closed = true
        question?.complete(null)
        question = null
    }
}
