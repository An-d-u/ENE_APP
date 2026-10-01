package dev.ene.companion

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.ene.companion.connection.ConnectionRepository
import dev.ene.companion.connection.androidPcDiscovery
import dev.ene.companion.connection.androidPreviousExit
import dev.ene.companion.storage.ConnectionSettingsStore
import dev.ene.companion.storage.TokenStore
import dev.ene.companion.audio.AndroidAudioPlatform
import dev.ene.companion.character.CharacterPlatform
import dev.ene.companion.character.CharacterPlacementController
import dev.ene.companion.storage.CharacterPlacementStore
import dev.ene.companion.storage.ChatLayoutStore
import dev.ene.companion.presentation.ChatLayoutController
import kotlinx.coroutines.*

/** 화면 회전과 별개인 앱 수명의 연결 하나만 유지한다. */
class EneApplication : Application(), DefaultLifecycleObserver {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var connection: ConnectionRepository
        private set
    lateinit var chatLayout: ChatLayoutController
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        val startedMillis = System.currentTimeMillis()
        chatLayout = ChatLayoutController(ChatLayoutStore(this), applicationScope)
        connection = ConnectionRepository(TokenStore(this), ConnectionSettingsStore(this),
            discovery = androidPcDiscovery(this),
            audioPlatform = AndroidAudioPlatform(this), characterPlatform = CharacterPlatform.android(this),
            placementController = CharacterPlacementController(CharacterPlacementStore(this), applicationScope))
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        applicationScope.launch {
            val previous = withContext(Dispatchers.IO) { androidPreviousExit(this@EneApplication, startedMillis) }
            connection.previousExit(previous)
        }
    }
    override fun onStart(owner: LifecycleOwner) { connection.foreground(true) }
    override fun onStop(owner: LifecycleOwner) { chatLayout.finishAdjustment(); connection.foreground(false) }
}
