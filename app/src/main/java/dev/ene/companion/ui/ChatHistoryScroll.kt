package dev.ene.companion.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import dev.ene.companion.connection.ConnectionPhase
import dev.ene.companion.connection.ConnectionViewState
import dev.ene.companion.presentation.*
import dev.ene.companion.protocol.PublicMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.roundToInt

internal class MessageChunks(val text: String, val split: Boolean) {
    val chunks = splitMessageBubbles(text, split).ifEmpty { listOf("") }
    val starts = messageBubbleOffsets(text, chunks)
}
internal data class MeasuredBubble(val start: Int, val geometry: BubbleGeometry)
private data class ChatScrollAnchor(val messageId: String, val sourceOffset: Int, val lineOffset: Float,
                                  val order: List<Pair<PublicMessage, MessageChunks>>)

/** 전체 문단을 행 객체로 복제하지 않고 보이는 행만 찾는다. */
private class HistoryRender(val groups: List<Pair<PublicMessage, MessageChunks>>, val split: Boolean, val progress: String?) {
    val starts = IntArray(groups.size + 1).also { starts ->
        groups.forEachIndexed { index, (_, parts) -> starts[index + 1] = starts[index] + parts.chunks.size }
    }
    val footerIndex = (if (groups.isEmpty()) 1 else starts.last()) + if (progress != null) 1 else 0
    fun row(index: Int): Triple<PublicMessage, MessageChunks, Int>? {
        if (index < 0 || index >= starts.last()) return null
        val group = starts.binarySearch(index).let { if (it >= 0) it else -it - 2 }
        return Triple(groups[group].first, groups[group].second, index - starts[group])
    }
    fun key(index: Int): String = row(index)?.let { (message, parts, chunk) ->
        messageBubbleKey(message.id, chunk, chunk == parts.chunks.lastIndex)
    } ?: when {
        index == footerIndex -> "chat-notices"
        groups.isEmpty() && index == 0 -> "chat-empty"
        else -> "chat-action-progress"
    }
}

private data class ScrollWork(val index: Int? = null, val distance: Float? = null)

/** 프로그램 이동은 이 조정기의 run 안에서만 수행한다. 새 작업은 이전 측정 대기를 취소한다. */
internal class ChatHistoryScroller(private val list: LazyListState,
                                   private val measurements: SnapshotStateMap<Pair<String, Boolean>, MeasuredBubble>) {
    private val policy = ChatHistoryUpdates()
    private val work = Channel<ScrollWork>(Channel.CONFLATED)
    private var render = HistoryRender(emptyList(), false, null)
    private var layoutKey: Any? = null
    private var lastState: ConnectionViewState? = null
    private var anchor: ChatScrollAnchor? = null
    private var pendingAnchor: ChatScrollAnchor? = null
    private var automatic = false
    private var manualCommand: ScrollWork? = null
    private var dragging = false
    private var viewportSize: Pair<Int, Int>? = null
    private var sizes = emptyMap<Any, Int>()
    var hasUnseen by mutableStateOf(false); private set

    fun update(state: ConnectionViewState, groups: List<Pair<PublicMessage, MessageChunks>>, split: Boolean, progress: String?, key: Any) {
        val previous = lastState
        lastState = state
        var reset = false
        if (state.phase == ConnectionPhase.CONNECTED && state.conversationScope != null && state.conversationRevision != null) {
            reset = policy.completedSnapshot(state.conversationScope, state.conversationRevision, state.messages)
        } else if (state.conversationScope == null && previous?.conversationScope != null) {
            policy.forget(); reset = true
        }
        val changed = render.groups !== groups || render.split != split || render.progress != progress || layoutKey != key || reset
        if (!changed) return
        if (reset) { anchor = null; pendingAnchor = null }
        else if (!policy.following && !policy.userScrolling) pendingAnchor = pendingAnchor ?: anchor
        render = HistoryRender(groups, split, progress)
        layoutKey = key
        policy.contentOrViewportChanged()
        publish()
        // 사용자가 이동 중이면 최신 렌더만 교체한다. 접근성 이동 명령을 덮어쓰지 않는다.
        if (!policy.userScrolling) work.trySend(ScrollWork())
    }

    private fun publish() { hasUnseen = policy.hasUnseen }

    fun userStarted() {
        if (policy.userScrolling) return
        policy.userScrollStarted()
        pendingAnchor = null
        work.trySend(ScrollWork())
    }

    fun drag(started: Boolean) {
        dragging = started
        if (started) {
            manualCommand = null
            userStarted()
            work.trySend(ScrollWork())
        } else observedLayout()
    }

    fun jump() {
        policy.jumpToLatest(); pendingAnchor = null; manualCommand = null; publish()
        work.trySend(ScrollWork())
    }

    fun userIndex(index: Int): Boolean {
        if (index !in 0..render.footerIndex) return false
        userStarted()
        val command = ScrollWork(index = index)
        manualCommand = command; work.trySend(command); return true
    }

    fun userDistance(distance: Float): Boolean {
        if (!distance.isFinite()) return false
        userStarted()
        val command = ScrollWork(distance = distance)
        manualCommand = command; work.trySend(command); return true
    }

    val nested = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) userStarted()
            return Offset.Zero
        }
    }

    /** 이전 본문·행 키의 측정은 새 렌더 버전의 읽음 판정에 쓰지 않는다. */
    private fun viewport(): HistoryViewport? {
        val info = list.layoutInfo
        if (info.totalItemsCount != render.footerIndex + 1 || info.visibleItemsInfo.isEmpty()) return null
        val fullRows = mutableMapOf<String, Int>()
        for (item in info.visibleItemsInfo) {
            if (item.key != render.key(item.index)) return null
            val row = render.row(item.index) ?: continue
            val (message, parts, chunk) = row
            val measured = measurements[item.key.toString() to render.split] ?: return null
            if (measured.geometry.layout.layoutInput.text.text != parts.chunks[chunk]) return null
            if (item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset) {
                fullRows[message.id] = (fullRows[message.id] ?: 0) + 1
            }
        }
        val full = info.visibleItemsInfo.mapNotNull { item -> render.row(item.index)?.let { (message, parts, _) ->
            message.id.takeIf { fullRows[it] == parts.chunks.size }
        } }.toSet()
        return HistoryViewport(policy.renderVersion, full, !list.canScrollForward)
    }

    private fun captureAnchor(): ChatScrollAnchor? {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { render.row(it.index) != null } ?: return null
        val (message, _, _) = render.row(item.index) ?: return null
        val measured = measurements[item.key.toString() to render.split] ?: return null
        val geometry = measured.geometry
        val y = -item.offset - geometry.textTop
        val line = geometry.layout.getLineForVerticalPosition(y.coerceAtLeast(0f))
        return ChatScrollAnchor(message.id, measured.start + geometry.layout.getLineStart(line),
            y - geometry.layout.getLineTop(line), render.groups)
    }

    fun observedLayout() {
        val info = list.layoutInfo
        val size = info.viewportSize.width to info.viewportSize.height
        val nextSizes = info.visibleItemsInfo.associate { it.key to it.size }
        val resized = viewportSize != null && (viewportSize != size || nextSizes.any { (key, value) -> sizes[key]?.let { it != value } == true })
        viewportSize = size; sizes = nextSizes
        if (list.isScrollInProgress && !automatic) userStarted()
        if (resized && !automatic && !policy.userScrolling) {
            if (!policy.following) pendingAnchor = pendingAnchor ?: anchor
            policy.contentOrViewportChanged()
            work.trySend(ScrollWork())
        }
        val view = viewport() ?: return
        if (!automatic && pendingAnchor == null) {
            policy.measured(view)
            if (policy.userScrolling && !list.isScrollInProgress && !dragging && manualCommand == null) policy.userScrollFinished(view)
            anchor = captureAnchor()
            publish()
        }
    }

    private suspend fun measuredFrame(): HistoryViewport {
        withFrameNanos { }
        return snapshotFlow { viewport() }.filterNotNull().first()
    }

    private suspend fun restore(saved: ChatScrollAnchor) {
        var group = render.groups.indexOfFirst { it.first.id == saved.messageId }
        if (group < 0) {
            val oldIndex = saved.order.indexOfFirst { it.first.id == saved.messageId }
            val current = render.groups.mapIndexed { index, value -> value.first.id to index }.toMap()
            for (distance in 1..saved.order.size) {
                group = listOf(oldIndex - distance, oldIndex + distance).firstNotNullOfOrNull { index ->
                    saved.order.getOrNull(index)?.first?.id?.let { current[it] }
                } ?: -1
                if (group >= 0) break
            }
        }
        if (group < 0) return
        val (message, parts) = render.groups[group]
        val source = if (message.id == saved.messageId) saved.sourceOffset.coerceIn(0, parts.text.length) else 0
        val chunk = messageBubbleAtOffset(parts.starts, source)
        val index = render.starts[group] + chunk
        list.scrollToItem(index)
        measuredFrame()
        val key = messageBubbleKey(message.id, chunk, chunk == parts.chunks.lastIndex) to render.split
        val measured = snapshotFlow { measurements[key]?.takeIf { it.geometry.layout.layoutInput.text.text == parts.chunks[chunk] } }
            .filterNotNull().first()
        val geometry = measured.geometry
        val line = geometry.layout.getLineForOffset((source - measured.start).coerceIn(0, parts.chunks[chunk].length))
        val offset = geometry.textTop + geometry.layout.getLineTop(line) + if (message.id == saved.messageId) saved.lineOffset else 0f
        list.scrollToItem(index, offset.roundToInt().coerceAtLeast(0))
    }

    suspend fun run() {
        work.receiveAsFlow().collectLatest { command ->
            if (command.index != null || command.distance != null) {
                try {
                    if (command.index != null) list.scrollToItem(command.index) else list.scrollBy(command.distance!!)
                    policy.userScrollFinished(measuredFrame()); anchor = captureAnchor(); publish()
                } finally { if (manualCommand === command) manualCommand = null }
                return@collectLatest
            }
            if (policy.userScrolling) return@collectLatest
            automatic = true
            try {
                measuredFrame()
                if (policy.following) {
                    do {
                        // 마지막 말풍선 뒤의 안내까지 포함한다. 큰 오프셋은 목록의 실제 끝으로 제한된다.
                        list.scrollToItem(render.footerIndex, Int.MAX_VALUE)
                        measuredFrame()
                    } while (list.canScrollForward)
                } else pendingAnchor?.let { restore(it); measuredFrame() }
                pendingAnchor = null
                viewport()?.let { policy.measured(it) }
                anchor = captureAnchor(); publish()
            } finally { automatic = false }
        }
    }
}

@Composable
internal fun rememberChatHistoryScroller(list: LazyListState,
    measurements: SnapshotStateMap<Pair<String, Boolean>, MeasuredBubble>): ChatHistoryScroller {
    val scroller = remember(list) { ChatHistoryScroller(list, measurements) }
    LaunchedEffect(scroller) { scroller.run() }
    LaunchedEffect(scroller) {
        snapshotFlow { Triple(list.layoutInfo, list.isScrollInProgress, measurements.toMap()) }
            .collect { scroller.observedLayout() }
    }
    LaunchedEffect(scroller) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> scroller.drag(true)
                is DragInteraction.Stop, is DragInteraction.Cancel -> scroller.drag(false)
            }
        }
    }
    return scroller
}

internal fun Modifier.chatHistoryInput(scroller: ChatHistoryScroller): Modifier = nestedScroll(scroller.nested).semantics {
    scrollBy { _, y -> scroller.userDistance(y) }
    scrollToIndex { scroller.userIndex(it) }
}
