package dev.ene.companion.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.ene.companion.connection.ConnectionException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

interface RecordingPlatform {
    suspend fun record(maximumBytes: Int): ByteArray
    fun finish()
    fun cancel()
}

/** 녹음은 메모리에만 보관한다. stop으로 blocking read를 깨우고 worker의 finally가 장치를 해제한다. */
class AndroidRecordingPlatform(private val context: Context) : RecordingPlatform {
    private var recorder: AudioRecord? = null
    private val stopping = AtomicBoolean()
    private val discarded = AtomicBoolean()
    override suspend fun record(maximumBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        require(maximumBytes in 2..960000 && maximumBytes % 2 == 0)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw ConnectionException("microphone_permission")
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw ConnectionException("microphone_unavailable")
        val jobContext = currentCoroutineContext()
        val native = synchronized(this@AndroidRecordingPlatform) {
            jobContext.ensureActive()
            check(recorder == null)
            stopping.set(false); discarded.set(false)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400)).also { recorder = it }
        }
        val bytes = ByteArray(maximumBytes)
        var used = 0
        try {
            if (native.state != AudioRecord.STATE_INITIALIZED) throw ConnectionException("microphone_unavailable")
            synchronized(this@AndroidRecordingPlatform) {
                if (stopping.get()) throw CancellationException("녹음 취소")
                jobContext.ensureActive()
                native.startRecording()
            }
            if (native.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw ConnectionException("microphone_unavailable")
            while (!stopping.get() && used < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = native.read(bytes, used, minOf(3200, bytes.size - used), AudioRecord.READ_BLOCKING)
                if (count < 0 && !stopping.get()) throw ConnectionException("microphone_failed")
                if (count > 0) used += count
            }
            currentCoroutineContext().ensureActive()
            if (discarded.get()) throw CancellationException("녹음 취소")
            bytes.copyOf(used - used % 2)
        } finally {
            synchronized(this@AndroidRecordingPlatform) {
                runCatching { native.stop() }; native.release()
                if (recorder === native) recorder = null
            }
            bytes.fill(0)
        }
    }
    @Synchronized override fun finish() { stopping.set(true); runCatching { recorder?.stop() } }
    @Synchronized override fun cancel() { discarded.set(true); finish() }
}
