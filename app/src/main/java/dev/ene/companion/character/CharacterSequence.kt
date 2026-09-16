package dev.ene.companion.character

import dev.ene.companion.protocol.CharacterAction
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 일회성 동작은 큐에 넣지 않는다. 다운로드/회전 중에도 관찰한 번호는 되돌리지 않는다. */
class CharacterSequence {
    var snapshot: CharacterSnapshot? = null
        private set
    var observedAction = 0L
        private set
    private var minimumRevision = 0L
    private var expectedVersion: String? = null
    private var fetching = true
    private var rendererReady = false

    fun changed(revision: Long, version: String?): Boolean {
        if (revision <= minimumRevision) return false
        minimumRevision = revision
        expectedVersion = version
        loading()
        return true
    }

    fun loading() { fetching = true; rendererReady = false }
    fun detached() { rendererReady = false }
    fun rendered(version: String) {
        if (!fetching && snapshot?.status == "ready" && snapshot?.modelVersion == version) rendererReady = true
    }

    fun install(next: CharacterSnapshot): Boolean {
        if (next.stateRevision < minimumRevision ||
            (minimumRevision > 0 && next.stateRevision == minimumRevision && next.modelVersion != expectedVersion)) return false
        minimumRevision = next.stateRevision
        expectedVersion = next.modelVersion
        snapshot = next
        observedAction = maxOf(observedAction, next.actionSeq)
        fetching = false
        rendererReady = false
        return true
    }

    fun action(action: CharacterAction): String {
        if (action.action_seq <= observedAction) return "ignore"
        val previous = observedAction
        observedAction = action.action_seq
        val current = snapshot ?: return "ignore"
        if (fetching || !rendererReady || action.model_version != current.modelVersion) return "ignore"
        if (action.action_seq != previous + 1) { loading(); return "refresh" }
        val key = when (action.kind) { "expression" -> "expression_ids"; "gesture" -> "gesture_ids"; else -> return "ignore" }
        if (current.json.getValue(key).jsonArray.none { it.jsonPrimitive.content == action.action_id }) return "ignore"
        if (action.kind == "expression") {
            // 이미 적용한 표정의 현재값만 보관한다. 전환 시간이나 동작 재생은 복원하지 않는다.
            snapshot = CharacterSnapshot.parse(JsonObject(current.json + mapOf(
                "default_expression" to JsonPrimitive(action.action_id), "action_seq" to JsonPrimitive(action.action_seq))).toString())
        }
        return "apply"
    }
}
