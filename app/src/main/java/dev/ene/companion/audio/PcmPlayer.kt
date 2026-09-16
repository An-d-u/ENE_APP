package dev.ene.companion.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

/** write는 항상 비차단이다. 한 발화의 모든 호출은 같은 직렬 실행 경계에서 수행한다. */
interface PcmSink {
    val initialized: Boolean
    val bufferBytes: Int
    val minimumStartFrames: Int get() = 0
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun playbackHead(): Int
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
}

interface PcmSinkFactory {
    fun minimumBufferBytes(sampleRate: Int, channels: Int): Int
    fun create(sampleRate: Int, channels: Int, bufferBytes: Int): PcmSink
}

class PcmPlayerException(val code: String) : IllegalStateException(code)
data class PcmPlayback(val playedFrames: Long, val mouthOpen: Float)

/** 네트워크와 focus를 소유하지 않는다. start 호출 권한은 AudioSession이 판단한다. */
class PcmPlayer(val sampleRate: Int, val channels: Int, factory: PcmSinkFactory) : Closeable {
    companion object {
        const val HTTP_SCRATCH_BYTES = 32768
        const val HTTP_BUFFER_BYTES = 8192
        const val PADDING_BYTES = 1024
    }
    init { require(sampleRate in 8000..48000 && channels in 1..2) }
    private val frameBytes = channels * 2
    private val playerId = UUID.randomUUID().toString()
    private val clock = PlaybackClock(playerId)
    private val envelope = PcmEnvelope(sampleRate, channels)
    private val sink: PcmSink
    private val buffer: PcmBuffer
    private val nativeCapacityBytes: Int
    private val padding = ByteArray(PADDING_BYTES)
    private var paddedFrames = 0L
    val reservedBytes: Int
    val queueCapacityBytes: Int get() = buffer.capacityBytes
    val maxChunkBytes: Int get() = min(32768, queueCapacityBytes) / frameBytes * frameBytes
    val receivedFrames: Long get() = buffer.receivedFrames
    val sourceEnded: Boolean get() = buffer.sourceEnded
    var writtenFrames = 0L
        private set
    private var playedFrames = 0L
    private var playing = false
    private var stopped = false
    private var released = false
    val bufferedFrames: Long get() = receivedFrames - playedFrames
    val ready: Boolean get() = !stopped && (sourceEnded || bufferedFrames * 5 >= sampleRate)

    init {
        val limit = min(sampleRate * frameBytes * 4, 1024 * 1024)
        val minimum = factory.minimumBufferBytes(sampleRate, channels)
        if (minimum <= 0 || minimum > limit) throw PcmPlayerException("unsupported_format")
        val requested = maxOf(minimum, ((sampleRate + 4) / 5) * frameBytes)
        val aligned = (requested + frameBytes - 1) / frameBytes * frameBytes
        if (aligned + HTTP_SCRATCH_BYTES + HTTP_BUFFER_BYTES + PADDING_BYTES + frameBytes > limit) {
            throw PcmPlayerException("audio_buffer_limit")
        }
        sink = try { factory.create(sampleRate, channels, aligned) }
            catch (_: Exception) { throw PcmPlayerException("audio_initialization_failed") }
        try {
            val actual = sink.bufferBytes
            if (!sink.initialized || actual <= 0 || actual % frameBytes != 0 ||
                actual.toLong() + HTTP_SCRATCH_BYTES + HTTP_BUFFER_BYTES + PADDING_BYTES + frameBytes > limit
            ) throw PcmPlayerException("audio_initialization_failed")
            nativeCapacityBytes = actual
            reservedBytes = actual + HTTP_SCRATCH_BYTES + HTTP_BUFFER_BYTES + PADDING_BYTES
            buffer = PcmBuffer(sampleRate, channels, reservedBytes)
        } catch (error: Exception) {
            runCatching { sink.release() }
            throw if (error is PcmPlayerException) error else PcmPlayerException("audio_initialization_failed")
        }
    }

    /** 호출자는 maxChunkBytes 이하로 나눈다. full이면 소비 후 같은 구간을 재시도한다. */
    fun offer(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
        if (stopped) "closed" else buffer.offer(bytes, offset, length)

    fun httpEof() { if (!stopped) buffer.finish() }

    fun start(): Boolean {
        if (playing || !ready) return false
        try { sink.play() } catch (_: Exception) {
            stop()
            throw PcmPlayerException("audio_start_failed")
        }
        playing = true
        return true
    }

    /** 50ms 위치 조회에 맞춰 호출한다. 한 호출의 비차단 쓰기도 8개로 제한한다. */
    fun pump(): PcmPlayback {
        if (stopped) return PcmPlayback(playedFrames, 0f)
        try {
            if (sink.bufferBytes > nativeCapacityBytes) throw PcmPlayerException("audio_buffer_limit")
            val raw = sink.playbackHead()
            val actual = clock.sample(playerId, raw, writtenFrames + paddedFrames)
                ?: throw PcmPlayerException("playback_clock_invalid")
            playedFrames = min(actual, writtenFrames)
            val mouth = envelope.mouthAt(playedFrames)
            for (index in 0 until 8) {
                val part = buffer.peek()
                if (part == null) {
                    if (!sourceEnded) break
                    val threshold = sink.minimumStartFrames
                    if (threshold !in 0..nativeCapacityBytes / frameBytes) {
                        throw PcmPlayerException("audio_buffer_limit")
                    }
                    val remaining = threshold - paddedFrames
                    if (remaining <= 0) break
                    // 구형 장치의 시작/언더런 임계값을 채우되 공개 길이에는 포함하지 않는다.
                    val length = min(remaining * frameBytes, padding.size.toLong()).toInt()
                    val count = sink.write(padding, 0, length)
                    if (count < 0 || count > length || count % frameBytes != 0) {
                        throw PcmPlayerException("audio_write_failed")
                    }
                    if (count == 0) break
                    paddedFrames += count / frameBytes
                    continue
                }
                val count = sink.write(part.bytes, part.offset, part.length)
                if (count < 0 || count > part.length || count % frameBytes != 0) {
                    throw PcmPlayerException("audio_write_failed")
                }
                if (count == 0) break
                if (!envelope.append(part.bytes, part.offset, count)) {
                    throw PcmPlayerException("audio_buffer_limit")
                }
                writtenFrames += count / frameBytes
                buffer.consume(count)
            }
            if (sourceEnded && buffer.peek() == null) envelope.finish()
            return PcmPlayback(playedFrames, mouth)
        } catch (error: Exception) {
            stop()
            throw if (error is PcmPlayerException) error else PcmPlayerException("audio_output_failed")
        }
    }

    fun drained(totalFrames: Long?): Boolean = playing && !stopped && sourceEnded &&
        totalFrames != null && receivedFrames == totalFrames && writtenFrames == totalFrames && playedFrames == totalFrames

    /** 즉시 무음으로 만들되 release는 reader/job 회수가 끝난 소유자가 호출한다. */
    fun stop() {
        if (stopped) return
        stopped = true
        playing = false
        // MODE_STREAM의 stop은 잔여 재생을 허용하므로 pause/flush를 먼저 수행한다.
        runCatching { sink.pause() }
        runCatching { sink.flush() }
        runCatching { sink.stop() }
        buffer.close()
    }

    override fun close() {
        stop()
        if (released) return
        released = true
        runCatching { sink.release() }
    }
}

internal fun speechAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

class AndroidPcmSinkFactory : PcmSinkFactory {
    private fun channelMask(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> throw PcmPlayerException("unsupported_format")
    }
    override fun minimumBufferBytes(sampleRate: Int, channels: Int): Int =
        AudioTrack.getMinBufferSize(sampleRate, channelMask(channels), AudioFormat.ENCODING_PCM_16BIT)

    override fun create(sampleRate: Int, channels: Int, bufferBytes: Int): PcmSink {
        val track = AudioTrack.Builder().setAudioAttributes(speechAudioAttributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(channelMask(channels)).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build()
        try {
            if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(1)
        } catch (error: Exception) {
            track.release()
            throw error
        }
        return object : PcmSink {
            override val initialized: Boolean get() = track.state == AudioTrack.STATE_INITIALIZED
            override val bufferBytes: Int get() = Math.multiplyExact(track.bufferCapacityInFrames, channels * 2)
            override val minimumStartFrames: Int get() = if (Build.VERSION.SDK_INT >= 31) {
                track.startThresholdInFrames
            } else track.bufferCapacityInFrames
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int = track.write(
                ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN), length, AudioTrack.WRITE_NON_BLOCKING,
            )
            override fun playbackHead(): Int = track.playbackHeadPosition
            override fun play() = track.play()
            override fun pause() = track.pause()
            override fun flush() = track.flush()
            override fun stop() = track.stop()
            override fun release() = track.release()
        }
    }
}
