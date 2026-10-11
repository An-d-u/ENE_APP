package dev.ene.companion.input

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalPhoto(val id: String, val mime: String, val sha256: String, val byteLength: Long,
                 val width: Int, val height: Int, val preview: ByteArray) {
    override fun toString() = "LocalPhoto($id,$width,$height)"
}
interface PhotoStorage {
    suspend fun initialize()
    suspend fun prepare(source: String): LocalPhoto
    suspend fun read(photo: LocalPhoto): ByteArray
    suspend fun delete(photos: List<LocalPhoto>)
}
data class PhotoDraftState(val images: List<LocalPhoto> = emptyList(), val preparing: Boolean = false,
                          val loaded: Boolean = false, val notice: String? = null)

/** 연결 단절·화면 재생성과 무관한 프로세스 전용 초안. 부분 준비 실패는 기존 초안을 보존한다. */
class PhotoDraft(private val storage: PhotoStorage, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PhotoDraftState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var preparation: Job? = null
    init {
        scope.launch {
            try { storage.initialize(); mutableState.value = mutableState.value.copy(loaded = true) }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(notice = "photo_storage_failed") }
        }
    }
    fun select(sources: List<String>) {
        if (sources.isEmpty() || preparation?.isActive == true || !state.value.loaded) return
        if (sources.size + state.value.images.size > 4) {
            mutableState.value = state.value.copy(notice = "photo_count_limit"); return
        }
        val expected = generation
        mutableState.value = state.value.copy(preparing = true, notice = null)
        preparation = scope.launch {
            val candidates = mutableListOf<LocalPhoto>()
            try {
                for (source in sources) {
                    candidates += storage.prepare(source)
                    if (generation != expected) break
                }
                if (generation == expected) {
                    mutableState.value = state.value.copy(images = state.value.images + candidates)
                    candidates.clear()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == expected) mutableState.value = state.value.copy(notice =
                    error.message?.takeIf { it in setOf("image_dimensions", "image_too_large", "unsupported_image", "animated_image", "invalid_image") }
                        ?: "photo_prepare_failed")
            } finally {
                withContext(NonCancellable) { storage.delete(candidates) }
                mutableState.value = state.value.copy(preparing = false)
            }
        }
    }
    fun remove(id: String) { accepted(setOf(id)) }
    fun accepted(ids: Set<String>) {
        val removed = state.value.images.filter { it.id in ids }
        mutableState.value = state.value.copy(images = state.value.images.filterNot { it.id in ids }, notice = null)
        scope.launch { storage.delete(removed) }
    }
    fun reset() {
        generation++
        accepted(state.value.images.map { it.id }.toSet())
        // 디코더의 취소 불가능한 작업은 끝난 뒤 후보를 회수한다. 결과 참조를 잃지 않는다.
    }
}
