package dev.ene.companion.character

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CharacterPlacementState(val placement: CharacterPlacement = CharacterPlacement(), val loaded: Boolean = false,
    val saveStatus: String = "idle", val readFailed: Boolean = false)

class CharacterPlacementController(private val storage: CharacterPlacementStorage, private val scope: CoroutineScope,
                                   private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutableState = MutableStateFlow(CharacterPlacementState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var savedRevision = 0L
    private var debounce: Job? = null

    init {
        scope.launch {
            val initial = try {
                CharacterPlacementState(withContext(ioDispatcher) { storage.load() } ?: CharacterPlacement(), loaded = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { CharacterPlacementState(loaded = true, readFailed = true) }
            mutableState.value = initial
            // 쓰기는 한 소비자가 끝까지 수행한다. 새 조절은 진행 중인 원자 쓰기를 취소하지 않는다.
            for (ignored in writes) {
                while (savedRevision < revision) {
                    debounce?.cancel(); debounce = null
                    val writingRevision = revision
                    val value = state.value.placement
                    mutableState.value = state.value.copy(saveStatus = "saving")
                    val success = try { withContext(ioDispatcher) { storage.save(value) }; true }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { false }
                    if (success) savedRevision = writingRevision
                    if (writingRevision == revision) {
                        mutableState.value = state.value.copy(saveStatus = if (success) "idle" else "error",
                            readFailed = if (success) false else state.value.readFailed)
                        // 대기 채널에 같은 번호의 요청이 있어도 실패를 무한 재시도하지 않는다.
                        if (!success) while (writes.tryReceive().isSuccess) { }
                        break
                    }
                }
            }
        }
    }

    fun change(value: CharacterPlacement) {
        if (!state.value.loaded || state.value.placement == value) return
        revision++
        mutableState.value = state.value.copy(placement = value, saveStatus = "dirty")
        debounce?.cancel()
        debounce = scope.launch { delay(250); writes.trySend(Unit) }
    }
    fun finishAdjustment() {
        if (!state.value.loaded || revision == savedRevision || state.value.saveStatus == "error") return
        debounce?.cancel(); debounce = null
        writes.trySend(Unit)
    }
    fun retrySave() {
        if (!state.value.loaded) return
        debounce?.cancel(); debounce = null
        revision++
        mutableState.value = state.value.copy(saveStatus = "dirty")
        writes.trySend(Unit)
    }
    fun reset() {
        if (!state.value.loaded) return
        mutableState.value = state.value.copy(placement = CharacterPlacement())
        retrySave()
    }
}
