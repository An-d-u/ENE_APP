package dev.ene.companion.audio

import android.content.Context

interface AudioPlatform {
    fun player(sampleRate: Int, channels: Int): PcmPlayer
    fun focus(interrupted: (String) -> Unit): AudioFocusController
}

class AndroidAudioPlatform(context: Context) : AudioPlatform {
    private val app = context.applicationContext
    override fun player(sampleRate: Int, channels: Int) = PcmPlayer(sampleRate, channels, AndroidPcmSinkFactory())
    override fun focus(interrupted: (String) -> Unit) = AudioFocusController(AndroidAudioFocusPort(app), interrupted)
}
