package dev.ene.companion.camera

import dev.ene.companion.connection.ObservationIntervals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 질문은 접수 뒤의 한 프레임만 받고 자동 관찰은 작은 색상 표본만 비교한다. */
internal class CameraFrameSampler(private val intervals: () -> ObservationIntervals) {
    private var question: CompletableDeferred<ByteArray?>? = null
    @Volatile private var closed = false
    // 분석 콜백은 단일 작업 스레드에서 호출된다.
    private var previous: IntArray? = null
    private var lastEpoch: Long? = null
    private var lastSample: Long? = null
    private var lastUpload: Long? = null

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
            lastSample = null
        }
        val candidate = epoch != null && sampleChanged(now, signature)
        if (request == null && !candidate) return
        val jpeg = encode()
        synchronized(this) {
            if (closed) return
            if (request != null && request === question) {
                question = null
                request.complete(jpeg)
            }
        }
        if (candidate && epoch != null && jpeg != null && !closed && observationEpoch() == epoch &&
            elapsed(now, lastUpload, intervals().requestSeconds)) {
            lastUpload = now
            observe(epoch, jpeg)
        }
    }

    private fun sampleChanged(now: Long, signature: () -> IntArray): Boolean {
        val timing = intervals()
        if (!elapsed(now, lastSample, timing.sampleSeconds)) return false
        lastSample = now
        val sample = signature()
        val old = previous
        val canUpload = elapsed(now, lastUpload, timing.requestSeconds)
        // 대기 중에도 새 장면을 확인한다. 대기가 끝나면 현재 프레임을 기준 장면과 비교한다.
        if (old == null || old.size != sample.size || canUpload) previous = sample
        if (old == null || old.size != sample.size) return false
        val changed = sample.indices.count { abs(old[it] - sample[it]) >= 24 }
        return canUpload && changed * 100 >= sample.size * 8
    }

    private fun elapsed(now: Long, last: Long?, seconds: Int) = last == null || now - last >= seconds * 1000L

    @Synchronized fun close() {
        closed = true
        question?.complete(null)
        question = null
    }
}
