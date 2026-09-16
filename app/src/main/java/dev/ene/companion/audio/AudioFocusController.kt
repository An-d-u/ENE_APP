package dev.ene.companion.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** 실제 Android 객체 대신 가상 권한과 분리 알림으로 같은 종료 경계를 검사한다. */
interface AudioFocusPort {
    fun request(onChange: (String) -> Unit): String
    fun abandon()
    fun watchNoisy(onNoisy: () -> Unit)
    fun unwatchNoisy()
}

/** Main 직렬 경계에서만 호출한다. 한 발화에 한 번만 요청하고 재개하지 않는다. */
class AudioFocusController(private val port: AudioFocusPort, private val interrupted: (String) -> Unit) : Closeable {
    private var attempted = false
    private var requested = false
    private var watching = false
    private var closed = false
    var granted = false
        private set

    fun acquire(): Boolean {
        if (closed || attempted) return granted
        attempted = true
        try {
            watching = true
            port.watchNoisy { terminate("audio_route_changed") }
            if (closed) return false
            requested = true
            val result = port.request { change ->
                if (change != "gained") terminate("audio_focus_lost")
            }
            if (closed) return false
            if (result != "granted") {
                close()
                return false
            }
            granted = true
            return true
        } catch (_: Exception) {
            close()
            return false
        }
    }

    private fun terminate(reason: String) {
        if (closed) return
        close()
        interrupted(reason)
    }

    override fun close() {
        if (closed) return
        closed = true
        granted = false
        if (requested) runCatching { port.abandon() }
        if (watching) runCatching { port.unwatchNoisy() }
        requested = false
        watching = false
    }
}

internal fun speechFocusRequest(listener: AudioManager.OnAudioFocusChangeListener, handler: Handler): AudioFocusRequest =
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(speechAudioAttributes())
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener, handler)
        .build()

class AndroidAudioFocusPort(context: Context) : AudioFocusPort {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var receiver: BroadcastReceiver? = null

    override fun request(onChange: (String) -> Unit): String {
        check(focus == null)
        val request = speechFocusRequest({ change ->
            onChange(when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> "gained"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "duck"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "transient"
                else -> "lost"
            })
        }, handler)
        focus = request
        return when (manager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
            else -> "denied"
        }
    }

    override fun abandon() {
        val request = focus ?: return
        focus = null
        manager.abandonAudioFocusRequest(request)
    }

    override fun watchNoisy(onNoisy: () -> Unit) {
        check(receiver == null)
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
            }
        }
        receiver = listener
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(listener, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(listener, filter, null, handler)
        }
    }

    override fun unwatchNoisy() {
        val listener = receiver ?: return
        receiver = null
        app.unregisterReceiver(listener)
    }
}
