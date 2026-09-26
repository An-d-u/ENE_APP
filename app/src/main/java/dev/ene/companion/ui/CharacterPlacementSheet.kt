package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ene.companion.character.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPlacementSheet(state: CharacterPlacementState, onChange: (CharacterPlacement) -> Unit,
                            onFinish: () -> Unit, onReset: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CharacterPlacementContent(state, onChange, onFinish, onReset, onRetry, onClose)
    }
}

@Composable
internal fun CharacterPlacementContent(state: CharacterPlacementState, onChange: (CharacterPlacement) -> Unit,
                                       onFinish: () -> Unit, onReset: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
        Text("휴대폰 표시 설정", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이 휴대폰에 자동 저장됩니다. PC의 크기·위치와 움직임 설정은 바꾸지 않습니다.", style = MaterialTheme.typography.bodyMedium)
            PlacementSlider("캐릭터 크기", state.placement.scale * 100, 50f..200f, state.loaded,
                { onChange(state.placement.copy(scale = it / 100)) }, onFinish)
            PlacementSlider("가로 위치", state.placement.xPercent, 0f..100f, state.loaded,
                { onChange(state.placement.copy(xPercent = it)) }, onFinish)
            PlacementSlider("세로 위치", state.placement.yPercent, 0f..100f, state.loaded,
                { onChange(state.placement.copy(yPercent = it)) }, onFinish)
            Text(when {
                !state.loaded -> "저장된 배치를 불러오는 중입니다."
                state.saveStatus == "error" -> "배치를 저장하지 못했습니다. 마지막 저장값은 유지됩니다."
                state.saveStatus in setOf("dirty", "saving") -> "저장 중…"
                state.readFailed -> "저장된 배치를 읽지 못해 기본값을 표시합니다. 조절하거나 기본 배치를 복원하면 다시 저장합니다."
                else -> "저장됨"
            }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            if (state.saveStatus == "error") OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("저장 재시도") }
            OutlinedButton(onClick = onReset, enabled = state.loaded, modifier = Modifier.heightIn(min = 48.dp)) { Text("기본 배치 복원") }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("닫기") }
    }
}

@Composable
private fun PlacementSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
                            onChange: (Double) -> Unit, onFinish: () -> Unit) {
    Column {
        Text("$label · ${value.roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.coerceIn(range).toDouble()) },
            onValueChangeFinished = onFinish, valueRange = range, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label; stateDescription = "${value.roundToInt()}%"
            })
    }
}
