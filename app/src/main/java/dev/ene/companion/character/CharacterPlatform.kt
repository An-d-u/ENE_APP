package dev.ene.companion.character

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

interface CharacterRenderer {
    fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?)
    fun post(type: String, value: JsonObject)
    fun clear() = Unit
}

data class CharacterViewState(val status: String = "unavailable", val errorCode: String? = null, val viewGeneration: Long = 0,
    val settings: CharacterSettingsViewState = CharacterSettingsViewState())

/** 렌더러와 디스크 경계를 시험 대역으로 교체할 수 있다. Application 단위로 한 번 생성한다. */
class CharacterPlatform(val supported: Boolean,
                        val clearCache: suspend () -> Unit = {},
                        val load: suspend (String, CharacterMedia, () -> Boolean) -> CharacterLoad) {
    companion object {
        fun android(context: Context): CharacterPlatform {
            val application = context.applicationContext
            val mutex = Mutex()
            var repository: CharacterRepository? = null
            suspend fun instance() = mutex.withLock {
                repository ?: CharacterRepository.create(application).also { repository = it }
            }
            return CharacterPlatform(CharacterWebView.supported(), clearCache = { instance().clear() }) { identity, media, current ->
                instance().load(identity, media, current)
            }
        }
    }
}
