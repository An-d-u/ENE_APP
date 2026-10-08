package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.presentation.*
import dev.ene.companion.protocol.PublicMessage

internal data class BubbleGeometry(val layout: TextLayoutResult, val textTop: Float)

private class BubbleGeometryReporter {
    var group: LayoutCoordinates? = null
    var text: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
    var onMeasured: (BubbleGeometry) -> Unit = {}
    fun publish() {
        val outer = group?.takeIf { it.isAttached } ?: return
        val inner = text?.takeIf { it.isAttached } ?: return
        val result = layout ?: return
        onMeasured(BubbleGeometry(result, outer.localPositionOf(inner, Offset.Zero).y))
    }
}

@Composable
internal fun ChatBubble(text: String, user: Boolean, maxWidth: Dp,
                        onLayout: (TextLayoutResult) -> Unit, onPositioned: (LayoutCoordinates) -> Unit) {
    Surface(color = if (user) ChatUserColor else ChatAssistantColor,
        contentColor = if (user) Color.White else Color(0xFF111827),
        modifier = Modifier.widthIn(max = maxWidth).testTag("chat-bubble")
            .semantics { contentDescription = if (user) "사용자 메시지" else "ENE 메시지" },
        shape = RoundedCornerShape(18.dp)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp).onGloballyPositioned(onPositioned),
            style = MaterialTheme.typography.bodyLarge, onTextLayout = onLayout)
    }
}

/** 마지막 말풍선의 고정 key가 분할 개수 변경에도 펼침 상태를 보존한다. */
@Composable
internal fun ChatMessageGroup(message: PublicMessage, text: String, last: Boolean,
                              actions: ChatActionsViewState, thought: ThoughtContent?,
                              expanded: Boolean, onToggleThought: () -> Unit,
                              onEdit: (String) -> Unit, onReroll: (String) -> Unit, onRetryThought: (String) -> Unit,
                              onMeasured: (BubbleGeometry) -> Unit) {
    val reporter = remember(text) { BubbleGeometryReporter() }
    reporter.onMeasured = onMeasured
    val user = message.role == "user"
    val alignment = if (user) Alignment.End else Alignment.Start
    val time = if (last) formatMessageTime(message.displayed_at) else null
    val hasThought = !user && thought?.status == "available"
    val action = messageAction(message.id, message.role, actions)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val timeWidth = time?.let { with(density) {
        measurer.measure(AnnotatedString(it), MaterialTheme.typography.labelSmall).size.width.toDp()
    } } ?: 0.dp
    val metaWidth = maxOf(timeWidth, (if (action != null) 48.dp else 0.dp) + if (hasThought) 48.dp else 0.dp)
    CompositionLocalProvider(LocalContentColor provides Color(0xFFD5DFEA)) {
        Column(Modifier.fillMaxWidth().padding(bottom = if (last) 14.dp else 6.dp)
            .onGloballyPositioned { reporter.group = it; reporter.publish() }, horizontalAlignment = alignment) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val below = messageMetaBelow(maxWidth.value, density.fontScale, metaWidth.value)
                val bubbleWidth = minOf(560.dp, if (!last || below) maxWidth * .88f else maxWidth - metaWidth - 8.dp)
                val meta: @Composable () -> Unit = {
                    ChatMessageMeta(message.id, message.role, time, actions, hasThought, expanded,
                        onToggleThought, onEdit, onReroll, Modifier.width(metaWidth))
                }
                Column(Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart), horizontalAlignment = alignment) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (last && !below && user) meta()
                        ChatBubble(text, user, bubbleWidth.coerceAtLeast(1.dp),
                            { reporter.layout = it; reporter.publish() }, { reporter.text = it; reporter.publish() })
                        if (last && !below && !user) meta()
                    }
                    if (last && below) meta()
                }
            }
            if (last) {
                if (message.attachment_unsupported == true) Text("첨부 내용은 PC에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
                ChatActionNotice(message.id, message.role, actions)
                if (!user) MessageThought(thought, expanded) { onRetryThought(message.id) }
            }
        }
    }
}
