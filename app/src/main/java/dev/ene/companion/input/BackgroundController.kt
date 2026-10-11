package dev.ene.companion.input

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class BackgroundSettings(val mode: String = "default", val color: Long = 0xFF18212C,
    val imageId: String? = null, val dim: Float = 0f, val blur: Int = 0) {
    fun checked(): BackgroundSettings {
        require(mode in setOf("default", "solid", "photo") && color in 0xFF000000..0xFFFFFFFF && dim.isFinite() && dim in 0f..0.75f && blur in 0..16)
        require(imageId == null || Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(imageId))
        require(mode != "photo" || imageId != null)
        return this
    }
}
interface BackgroundStorage {
    suspend fun load(): BackgroundSettings?
    suspend fun save(settings: BackgroundSettings)
    suspend fun prepare(source: String): String
    suspend fun image(id: String): ByteArray
    suspend fun cleanup(keep: Set<String>)
}
data class BackgroundState(val settings: BackgroundSettings = BackgroundSettings(), val image: ByteArray? = null,
    val loaded: Boolean = false, val status: String = "idle") {
    override fun toString() = "BackgroundState(loaded=$loaded,status=$status)"
}

/** 이 조정기는 연결·캐릭터 소유자를 알지 못한다. 저장 성공 전에는 이전 사진을 회수하지 않는다. */
class BackgroundController(private val storage: BackgroundStorage, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(BackgroundState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private var debounce: Job? = null
    private var selecting: Job? = null
    private var revision = 0L
    private var persisted = BackgroundSettings()
    init {
        scope.launch {
            try {
                persisted = (storage.load() ?: BackgroundSettings()).checked()
                val bytes = persisted.imageId?.let { storage.image(it) }
                mutableState.value = BackgroundState(persisted, bytes, true)
                storage.cleanup(setOfNotNull(persisted.imageId))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = BackgroundState(loaded = true, status = "error") }
            for (ignored in writes) lock.withLock {
                val selected = state.value.settings
                val expected = revision
                try {
                    storage.save(selected)
                    persisted = selected
                    storage.cleanup(setOfNotNull(selected.imageId))
                    if (revision == expected) mutableState.value = state.value.copy(status = "idle")
                    else writes.trySend(Unit)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { mutableState.value = state.value.copy(status = "error") }
            }
        }
    }
    fun change(value: BackgroundSettings) {
        if (!state.value.loaded || selecting?.isActive == true) return
        value.checked()
        revision++
        mutableState.value = state.value.copy(settings = value, image = if (value.imageId == null) null else state.value.image, status = "dirty")
        debounce?.cancel(); debounce = scope.launch { delay(250); writes.trySend(Unit) }
    }
    fun finish() { debounce?.cancel(); if (state.value.loaded && state.value.status in setOf("dirty", "error")) writes.trySend(Unit) }
    fun reset() { change(BackgroundSettings()); finish() }
    fun select(source: String) {
        if (!state.value.loaded || selecting?.isActive == true) return
        debounce?.cancel()
        selecting = scope.launch {
            lock.withLock {
                mutableState.value = state.value.copy(status = "preparing")
                try {
                    val id = storage.prepare(source)
                    val bytes = storage.image(id)
                    val candidate = state.value.settings.copy(mode = "photo", imageId = id)
                    storage.save(candidate)
                    persisted = candidate; revision++
                    mutableState.value = BackgroundState(candidate, bytes, true)
                    storage.cleanup(setOf(id))
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    storage.cleanup(setOfNotNull(persisted.imageId))
                    mutableState.value = state.value.copy(status = "error")
                }
            }
        }
    }
}
