package dev.ene.companion.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatLayoutState(val layout: ChatLayout = ChatLayout(), val loaded: Boolean = false,
    val saveStatus: String = "idle", val readFailed: Boolean = false)

/** 대화 내용과 분리된 휴대폰 표시 설정. 쓰기는 직렬화하고 최신 조절값을 보존한다. */
class ChatLayoutController(private val storage: ChatLayoutStorage, private val scope: CoroutineScope,
                           private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val mutableState = MutableStateFlow(ChatLayoutState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var savedRevision = 0L
    private var debounce: Job? = null

    init {
        scope.launch {
            mutableState.value = try {
                ChatLayoutState(withContext(ioDispatcher) { storage.load() } ?: ChatLayout(), loaded = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { ChatLayoutState(loaded = true, readFailed = true) }
            for (ignored in writes) {
                while (savedRevision < revision) {
                    debounce?.cancel(); debounce = null
                    val writingRevision = revision
                    val value = state.value.layout
                    mutableState.value = state.value.copy(saveStatus = "saving")
                    val success = try { withContext(ioDispatcher) { storage.save(value) }; true }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { false }
                    if (success) savedRevision = writingRevision
                    if (writingRevision == revision) {
                        mutableState.value = state.value.copy(saveStatus = if (success) "idle" else "error",
                            readFailed = if (success) false else state.value.readFailed)
                        if (!success) while (writes.tryReceive().isSuccess) { }
                        break
                    }
                }
            }
        }
    }

    fun change(fraction: Float) {
        if (!state.value.loaded || !fraction.isFinite()) return
        val value = ChatLayout(fraction.coerceIn(ChatLayout.MIN_FRACTION, ChatLayout.MAX_FRACTION))
        if (state.value.layout == value) return
        revision++
        mutableState.value = state.value.copy(layout = value, saveStatus = "dirty")
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
        mutableState.value = state.value.copy(layout = ChatLayout())
        retrySave()
    }
}
