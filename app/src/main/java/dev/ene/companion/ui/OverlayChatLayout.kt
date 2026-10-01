package dev.ene.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ene.companion.presentation.*
import kotlin.math.roundToInt

internal val ChatPanelColor = Color(0xFF111214).copy(alpha = .78f)
internal val ChatUserColor = Color(0xFF0071E3).copy(alpha = .94f)
internal val ChatAssistantColor = Color.White.copy(alpha = .96f)

/** IME 여백은 전경에만 적용한다. 배경은 패널 높이와 관계없이 같은 크기를 유지한다. */
@Composable
internal fun OverlayChatLayout(state: ChatLayoutState, onHeight: (Float) -> Unit, onFinish: () -> Unit,
                               scene: @Composable () -> Unit, toolbar: @Composable () -> Unit,
                               modifier: Modifier = Modifier, content: @Composable ColumnScope.(Boolean) -> Unit) {
    val density = LocalDensity.current
    val keyboard = WindowInsets.ime.getBottom(density) > 0
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF152331), Color(0xFF304858), Color(0xFF18212C))))) {
        Box(Modifier.matchParentSize()) { scene() }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding().navigationBarsPadding()) {
            val hideToolbar = chatHidesToolbar(maxHeight.value, keyboard, density.fontScale)
            Column(Modifier.fillMaxSize()) {
                if (!hideToolbar) toolbar()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val availableHeight = maxHeight.value
                    val height = chatPanelHeight(availableHeight, state.layout.heightFraction, density.fontScale)
                    val compact = chatUsesCompactControls(availableHeight, density.fontScale)
                    // Surface 자체가 배경 터치 전파를 막는다. 별도 소비기는 자식 드래그를 취소할 수 있다.
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height.dp)
                        .testTag("overlay-chat-panel"), color = ChatPanelColor, contentColor = Color.White,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                        Column {
                            if (!compact) ChatResizeHandle(state, height, availableHeight, onHeight, onFinish)
                            content(compact)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatResizeHandle(state: ChatLayoutState, height: Float, available: Float,
                             onHeight: (Float) -> Unit, onFinish: () -> Unit) {
    val density = LocalDensity.current
    var dragHeight by remember { mutableFloatStateOf(0f) }
    val enabled = state.loaded && available > 0f &&
        chatPanelHeight(available, ChatLayout.MIN_FRACTION, density.fontScale) < chatPanelHeight(available, ChatLayout.MAX_FRACTION, density.fontScale)
    val fraction = state.layout.heightFraction
    fun adjust(value: Float): Boolean {
        if (!state.loaded || !value.isFinite()) return false
        onHeight(value.coerceIn(ChatLayout.MIN_FRACTION, ChatLayout.MAX_FRACTION)); onFinish()
        return true
    }
    Box(Modifier.fillMaxWidth().height(48.dp).semantics {
        contentDescription = "대화창 높이"
        stateDescription = "${(fraction * 100).roundToInt()}%"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, ChatLayout.MIN_FRACTION..ChatLayout.MAX_FRACTION)
        if (!state.loaded) disabled()
        setProgress { adjust(it) }
        customActions = listOf(CustomAccessibilityAction("대화창 늘리기") { adjust(fraction + .05f) },
            CustomAccessibilityAction("대화창 줄이기") { adjust(fraction - .05f) })
    }.focusable().draggable(orientation = Orientation.Vertical, enabled = enabled,
        state = rememberDraggableState { delta ->
            val next = chatFractionAfterDrag(dragHeight, delta / density.density, available)
            dragHeight = chatPanelHeight(available, next, density.fontScale)
            onHeight(next)
        }, onDragStarted = { dragHeight = height }, onDragStopped = { onFinish() }), contentAlignment = Alignment.Center) {
        Box(Modifier.width(40.dp).height(4.dp).background(Color.White.copy(alpha = .55f), RoundedCornerShape(2.dp)))
    }
}

@Composable
internal fun CompanionToolbar(status: String, registered: Boolean, commonSettingsEnabled: Boolean,
                              onPlacement: () -> Unit, onAction: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = Color(0xFF111214).copy(alpha = .7f), contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text("ENE", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.labelSmall, color = Color(0xFFD5DFEA))
            }
            TextButton(onClick = onPlacement, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("배치") }
            Box {
                TextButton(onClick = { expanded = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "연결 및 앱 설정" }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Text("⋯", style = MaterialTheme.typography.headlineSmall)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    fun select(action: String) { expanded = false; onAction(action) }
                    DropdownMenuItem(text = { Text("QR 연결") }, onClick = { select("pair") })
                    DropdownMenuItem(text = { Text("PC 주소 수정") }, enabled = registered, onClick = { select("address") })
                    DropdownMenuItem(text = { Text("연결·재생 상태") }, onClick = { select("status") })
                    DropdownMenuItem(text = { Text("연결 진단") }, onClick = { select("diagnostics") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("휴대폰 배치") }, onClick = { expanded = false; onPlacement() })
                    DropdownMenuItem(text = { Text("캐릭터 공통 설정") }, enabled = commonSettingsEnabled, onClick = { select("character") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("등록 해제", color = MaterialTheme.colorScheme.error) }, enabled = registered, onClick = { select("forget") })
                }
            }
        }
    }
}
