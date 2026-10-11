package dev.ene.companion.presentation

import dev.ene.companion.connection.ConversationScope
import dev.ene.companion.protocol.PublicMessage

internal enum class HistoryMove { LATEST, ANCHOR }
internal data class HistoryViewport(val renderVersion: Long, val fullyVisibleIds: Set<String>, val atBottom: Boolean)

/** 완성 이력의 참조만 보관한다. TTS·생각·연결 상태 갱신마다 본문을 다시 해시하지 않는다. */
internal class ChatHistoryUpdates {
    var following = true; private set
    var hasUnseen = false; private set
    var userScrolling = false; private set
    var renderVersion = 0L; private set
    val move get() = if (following) HistoryMove.LATEST else HistoryMove.ANCHOR
    private var scope: ConversationScope? = null
    private var revision = -1L
    private var messages: List<PublicMessage>? = null
    private val pendingIds = mutableSetOf<String>()

    /** 반환값은 대화 범위 변경 여부다. 미완성 동기화 목록은 호출자가 전달하지 않는다. */
    fun completedSnapshot(value: ConversationScope, nextRevision: Long, next: List<PublicMessage>): Boolean {
        if (scope != value) {
            forget()
            scope = value; revision = nextRevision; messages = next
            return true
        }
        if (nextRevision < revision) return false
        revision = nextRevision
        if (next === messages) return false
        val previous = messages.orEmpty().associateBy { it.id }
        val ids = next.mapTo(mutableSetOf()) { it.id }
        pendingIds.retainAll(ids)
        for (message in next) {
            val old = previous[message.id]
            if (old == null || old.role != message.role || old.text != message.text) pendingIds.add(message.id)
        }
        messages = next
        renderVersion++
        return false
    }

    fun measured(viewport: HistoryViewport) {
        if (viewport.renderVersion != renderVersion) return
        if (!following && pendingIds.any { it !in viewport.fullyVisibleIds }) hasUnseen = true
        pendingIds.clear()
    }

    fun userScrollStarted() { userScrolling = true; following = false }

    fun userScrollFinished(viewport: HistoryViewport) {
        if (viewport.renderVersion != renderVersion) return
        measured(viewport)
        if (userScrolling && viewport.atBottom) jumpToLatest()
        userScrolling = false
    }

    fun jumpToLatest() {
        following = true; hasUnseen = false; userScrolling = false; pendingIds.clear()
    }

    fun contentOrViewportChanged(): HistoryMove {
        renderVersion++
        return move
    }

    fun forget() {
        scope = null; revision = -1; messages = null; pendingIds.clear()
        following = true; hasUnseen = false; userScrolling = false
        renderVersion++
    }
}
