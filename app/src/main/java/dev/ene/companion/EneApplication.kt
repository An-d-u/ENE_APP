package dev.ene.companion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.ene.companion.connection.ConnectionRepository
import dev.ene.companion.storage.ConnectionSettingsStore
import dev.ene.companion.storage.TokenStore
import dev.ene.companion.audio.AndroidAudioPlatform
import dev.ene.companion.character.CharacterPlatform

/** 화면 회전과 별개인 앱 수명의 연결 하나만 유지한다. */
class EneApplication : Application(), DefaultLifecycleObserver {
    lateinit var connection: ConnectionRepository
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        connection = ConnectionRepository(TokenStore(this), ConnectionSettingsStore(this),
            audioPlatform = AndroidAudioPlatform(this), characterPlatform = CharacterPlatform.android(this))
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    override fun onStart(owner: LifecycleOwner) { connection.foreground(true) }
    override fun onStop(owner: LifecycleOwner) { connection.foreground(false) }
}
