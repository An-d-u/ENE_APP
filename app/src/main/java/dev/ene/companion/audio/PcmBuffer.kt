package dev.ene.companion.audio

import kotlin.math.min
import kotlin.math.sqrt

/** 쓰기 중에만 빌리는 구간이다. 소비자는 bytes를 변경하거나 별도 큐에 누적하지 않는다. */
data class PcmSlice(val bytes: ByteArray, val offset: Int, val length: Int)

/** reservedBytes에는 native buffer와 HTTP 읽기용 임시 공간을 함께 포함한다. */
class PcmBuffer(val sampleRate: Int, val channels: Int, reservedBytes: Int = 0) {
    init { require(sampleRate in 8000..48000 && channels in 1..2) }
    val frameBytes = channels * 2
    val capacityBytes = min(sampleRate * frameBytes * 4, 1024 * 1024) - reservedBytes
    init { require(reservedBytes >= 0 && capacityBytes >= 0 && reservedBytes % frameBytes == 0) }
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    var retainedBytes = 0
        private set
    var receivedFrames = 0L
        private set
    var sourceEnded = false
        private set
    private var closed = false

    @Synchronized fun offer(bytes: ByteArray): String {
        if (closed || sourceEnded) return "closed"
        if (bytes.isEmpty() || bytes.size > 32768 || bytes.size % frameBytes != 0 ||
            (receivedFrames + bytes.size / frameBytes) * frameBytes >
                min(sampleRate * frameBytes * 180L, 36L * 1024 * 1024)
        ) return "invalid"
        if (retainedBytes + bytes.size > capacityBytes || chunks.size >= 256) return "full"
        chunks.addLast(bytes.copyOf())
        retainedBytes += bytes.size
        receivedFrames += bytes.size / frameBytes
        return "accepted"
    }

    @Synchronized fun peek(maxBytes: Int = 32768): PcmSlice? {
        require(maxBytes in 1..32768 && maxBytes % frameBytes == 0)
        val bytes = chunks.firstOrNull() ?: return null
        return PcmSlice(bytes, offset, min(maxBytes, bytes.size - offset))
    }

    @Synchronized fun consume(byteCount: Int) {
        val first = chunks.firstOrNull()
        require(first != null && byteCount in 0..first.size - offset && byteCount % frameBytes == 0)
        offset += byteCount
        if (offset == first.size) {
            retainedBytes -= chunks.removeFirst().size
            offset = 0
        }
    }

    @Synchronized fun finish() { sourceEnded = true }

    @Synchronized fun close() {
        closed = true
        chunks.clear()
        retainedBytes = 0
        offset = 0
    }
}

/** PCM은 보관하지 않고 50ms RMS만 대기시킨다. 실제 재생이 지나간 구간만 입에 적용한다. */
class PcmEnvelope(private val sampleRate: Int, private val channels: Int) {
    init { require(sampleRate in 8000..48000 && channels in 1..2) }
    private data class Window(val endFrame: Long, val mouth: Float)
    private val windows = ArrayDeque<Window>()
    private val frameBytes = channels * 2
    private val windowFrames = sampleRate / 20
    private var sumSquares = 0.0
    private var partialFrames = 0
    private var writtenFrames = 0L
    private var consumedFrames = 0L
    private var mouth = 0f
    private var ended = false
    val pendingWindows: Int get() = windows.size

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        if (ended || offset < 0 || length !in 1..32768 || offset > bytes.size - length ||
            length % frameBytes != 0 || writtenFrames + length / frameBytes > sampleRate * 180L
        ) return false
        // 부분 구간까지 올림해 예약하므로 EOF 때 별도 무제한 저장 공간이 필요 없다.
        val needed = (partialFrames + length / frameBytes + windowFrames - 1) / windowFrames
        if (windows.size + needed > 80) return false
        for (frame in offset until offset + length step frameBytes) {
            for (channel in 0 until channels) {
                val index = frame + channel * 2
                val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort()
                val normalized = sample.toDouble() / 32768.0
                sumSquares += normalized * normalized
            }
            writtenFrames++
            partialFrames++
            if (partialFrames == windowFrames) flushWindow()
        }
        return true
    }

    private fun flushWindow() {
        if (partialFrames == 0) return
        windows.addLast(Window(writtenFrames, min(1.0, sqrt(sumSquares / (partialFrames * channels)) * 3).toFloat()))
        partialFrames = 0
        sumSquares = 0.0
    }

    fun finish() {
        if (ended) return
        ended = true
        flushWindow()
    }

    fun mouthAt(playedFrames: Long): Float {
        if (playedFrames !in consumedFrames..writtenFrames) return mouth
        consumedFrames = playedFrames
        while (windows.isNotEmpty() && windows.first().endFrame <= playedFrames) mouth = windows.removeFirst().mouth
        return mouth
    }
}
