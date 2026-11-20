package dev.ene.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ObservationIntervals
import dev.ene.companion.connection.ObservationIntervalsView

@Composable
internal fun ObservationIntervalDialog(state: ObservationIntervalsView, connected: Boolean,
                                      onApply: (Int, Int, Int?) -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val loaded = connected && state.supported && state.loaded
    val current = state.intervals
    // 다른 기기에서 한 값만 바꾸면 나머지 입력칸에서 작성 중인 값은 보존한다.
    var sample by remember(loaded, current.sampleSeconds) { mutableStateOf(current.sampleSeconds.toString()) }
    var request by remember(loaded, current.requestSeconds) { mutableStateOf(current.requestSeconds.toString()) }
    var turns by remember(loaded, current.visualContextTurns) { mutableStateOf(current.visualContextTurns?.toString().orEmpty()) }
    var submitted by remember(loaded) { mutableStateOf<ObservationIntervals?>(null) }
    val sampleSeconds = sample.toIntOrNull()?.takeIf { it in ObservationIntervals.SECONDS_RANGE }
    val requestSeconds = request.toIntOrNull()?.takeIf { it in ObservationIntervals.SECONDS_RANGE }
    val supportsContext = current.visualContextTurns != null
    val contextTurns = turns.toIntOrNull()?.takeIf { it in ObservationIntervals.CONTEXT_TURNS_RANGE }
    val contextValid = !supportsContext || contextTurns != null
    val changed = current.sampleSeconds != sampleSeconds || current.requestSeconds != requestSeconds ||
        supportsContext && current.visualContextTurns != contextTurns
    AlertDialog(onDismissRequest = onClose, title = { Text("화면 관찰 설정") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loaded) {
                Text("PC 창과 휴대폰 카메라에 함께 적용합니다. 기본 간격은 각각 30초와 300초(5분)입니다.")
                IntervalField("화면 변화 확인 간격 (초)", sample, sampleSeconds != null, !state.saving) { sample = it }
                IntervalField("모델 호출 최소 간격 (초)", request, requestSeconds != null, !state.saving) { request = it }
                if (supportsContext) {
                    OutlinedTextField(value = turns, onValueChange = { turns = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("화면 정보 유지 턴 수") }, enabled = !state.saving, singleLine = true,
                        isError = !contextValid, supportingText = {
                            Text(if (contextValid) "기본 5턴 · 0은 다음 대화에 포함 안 함" else "0~20 사이의 정수를 입력해 주세요.")
                        }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("질문과 답변 한 쌍을 1턴으로 셉니다. 화면 없이 대화해도 턴이 지나며, 설정한 횟수의 후속 질문에 화면 요약을 전달합니다.")
                } else {
                    Text("PC ENE를 업데이트하면 화면 정보 유지 턴 수를 조절할 수 있습니다.")
                }
                when {
                    state.saving -> Text("PC에 저장 중…")
                    state.error -> Text("설정을 저장하지 못했습니다. 다시 적용해 주세요.", color = MaterialTheme.colorScheme.error)
                    submitted == current && !changed -> Text("PC와 휴대폰에 적용했습니다.")
                }
            } else {
                Text(when {
                    !connected -> "PC ENE에 연결하면 간격을 조절할 수 있습니다."
                    !state.supported -> "공유 간격 설정을 지원하는 PC ENE가 필요합니다."
                    state.error -> "PC의 관찰 간격을 불러오지 못했습니다."
                    else -> "PC의 관찰 간격을 불러오는 중…"
                })
            }
        }
    }, confirmButton = {
        if (loaded) {
            Button(enabled = !state.saving && changed && sampleSeconds != null && requestSeconds != null && contextValid, onClick = {
                if (sampleSeconds != null && requestSeconds != null && contextValid) {
                    val savedTurns = contextTurns.takeIf { supportsContext }
                    submitted = ObservationIntervals(sampleSeconds, requestSeconds, savedTurns)
                    onApply(sampleSeconds, requestSeconds, savedTurns)
                }
            }) { Text("적용") }
        } else if (connected && state.supported && state.error) {
            TextButton(onClick = onRetry) { Text("다시 불러오기") }
        }
    }, dismissButton = { TextButton(onClick = onClose) { Text("닫기") } })
}

@Composable
private fun IntervalField(label: String, value: String, valid: Boolean, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, enabled = enabled, singleLine = true, isError = !valid,
        supportingText = if (valid) null else { { Text("1~86,400 사이의 정수를 입력해 주세요.") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
