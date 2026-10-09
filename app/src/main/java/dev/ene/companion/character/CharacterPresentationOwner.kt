package dev.ene.companion.character

internal data class CharacterPresentationBinding(val sequence: Long, val identity: String)

internal enum class CharacterStopReason {
    BACKGROUND, TRANSIENT_DISCONNECT, SAME_IDENTITY_RETRY,
    REGISTRATION_CHANGE, TRUST_FAILURE, UNSUPPORTED, RENDERER_FAILURE,
    EXPLICIT_RELOAD, VIEW_RELEASED, CLOSED;

    val mayRetain: Boolean
        get() = this == BACKGROUND || this == TRANSIENT_DISCONNECT || this == SAME_IDENTITY_RETRY
}

/** Main 전용. 연결 자원 없이 현재 View와 준비된 모델 하나의 표시 권한만 소유한다. */
internal class CharacterPresentationOwner(private val onState: (CharacterViewState) -> Unit) {
    private var binding: CharacterPresentationBinding? = null
    private var identity: String? = null
    private var bindingSequence = 0L
    private var generation = 0L
    private var viewGeneration = 0L
    private var renderer: CharacterRenderer? = null
    private var snapshot: CharacterSnapshot? = null
    private var prepared = false
    private var applied = false
    private var resumed = false
    private var placement = CharacterPlacement()
    private var sessionState = CharacterViewState()
    var state = CharacterViewState()
        private set

    fun isCurrent(value: CharacterPresentationBinding) = binding == value

    fun bind(value: String): CharacterPresentationBinding {
        if (identity != null && identity != value) discard(CharacterStopReason.REGISTRATION_CHANGE)
        pause()
        identity = value
        return CharacterPresentationBinding(++bindingSequence, value).also { binding = it }
    }

    fun resumed(value: Boolean) {
        resumed = value
        if (!value) pause() else publish()
    }

    fun attach(value: CharacterRenderer) {
        if (renderer === value) return
        renderer?.let { runCatching { it.present(placement, false); it.clear() } }
        renderer = value; prepared = false; applied = false; snapshot = null
        invalidate()
        publish()
    }

    fun detach(value: CharacterRenderer) {
        if (renderer !== value) return
        runCatching { value.present(placement, false); value.clear() }
        renderer = null; snapshot = null; prepared = false; applied = false
        publish()
    }

    fun update(value: CharacterPresentationBinding, next: CharacterViewState) {
        if (!isCurrent(value)) return
        sessionState = next
        publish()
    }

    fun show(value: CharacterPresentationBinding, next: CharacterSnapshot, character: CharacterCache.CachedCharacter?) {
        if (!isCurrent(value)) return
        val target = renderer ?: return
        if (snapshot?.modelVersion != next.modelVersion || snapshot?.entryAssetId != next.entryAssetId || snapshot?.assets != next.assets) {
            prepared = false
            if (snapshot != null) runCatching { target.present(placement, false); target.clear() }
        }
        snapshot = next
        invalidate()
        render { it.show(next, character) }
        if (next.status != "ready") prepared = false
        publish()
    }

    fun present(value: CharacterPresentationBinding, next: CharacterPlacement, visible: Boolean) {
        if (!isCurrent(value)) return
        placement = next
        sessionState = sessionState.copy(presentationAllowed = visible)
        publish()
    }

    fun render(value: CharacterPresentationBinding, block: (CharacterRenderer) -> Unit) {
        if (isCurrent(value)) render(block)
    }

    fun fail(value: CharacterPresentationBinding, code: String) {
        if (!isCurrent(value)) return
        discard(CharacterStopReason.RENDERER_FAILURE)
        binding = value
        sessionState = CharacterViewState("error", code)
        publish()
    }

    fun event(source: CharacterRenderer, event: CharacterEvent): Boolean {
        if (renderer !== source) return false
        if (event.type == "document_error" || event.type == "error" && event.code == "character_initialization_failed") {
            rendererFailed(source, event.code ?: "character_render_failed")
            return false
        }
        if (binding == null || !resumed || event.presentationGeneration != generation) return false
        if (event.type == "ready") {
            if (snapshot?.status != "ready" || event.modelVersion != snapshot?.modelVersion) return false
            prepared = true; applied = true
        }
        return true
    }

    fun pause() {
        invalidate()
        sessionState = sessionState.copy(presentationAllowed = false)
        publish()
    }

    fun release(value: CharacterPresentationBinding, reason: CharacterStopReason) {
        if (!isCurrent(value)) return
        if (!reason.mayRetain || !prepared || renderer == null) {
            discard(reason)
            return
        }
        pause(); binding = null
        sessionState = CharacterViewState("refreshing")
        publish()
    }

    fun discard(reason: CharacterStopReason) {
        val previous = renderer
        invalidate()
        runCatching { previous?.clear() }
        if (renderer != null || state.retainRenderer) viewGeneration++
        renderer = null; binding = null; snapshot = null; prepared = false; applied = false
        sessionState = CharacterViewState(if (reason == CharacterStopReason.UNSUPPORTED) "unsupported" else "unavailable")
        publish()
    }

    fun rendererFailed(source: CharacterRenderer, code: String) {
        if (renderer !== source) return
        discard(CharacterStopReason.RENDERER_FAILURE)
        sessionState = CharacterViewState("error", code)
        publish()
    }

    private fun invalidate() {
        applied = false
        check(generation < 9_007_199_254_740_991L) { "character_generation_exhausted" }
        generation++
        render { it.present(placement, false); it.bindPresentation(generation) }
    }

    private fun publish() {
        val next = sessionState.copy(viewGeneration = viewGeneration,
            retainRenderer = sessionState.retainRenderer || prepared,
            presentationAllowed = resumed && binding != null && applied && sessionState.presentationAllowed)
        if (next != state) { state = next; onState(next) }
        render { it.present(placement, next.presentationAllowed) }
    }

    private fun render(block: (CharacterRenderer) -> Unit) {
        val target = renderer ?: return
        try { block(target) } catch (_: Exception) {
            // 실패 정리에서 같은 렌더러를 다시 호출하며 재귀하지 않는다.
            renderer = null
            runCatching { target.clear() }
            binding = null; prepared = false; applied = false; snapshot = null; viewGeneration++
            sessionState = CharacterViewState("error", "character_render_failed")
            publish()
        }
    }
}
