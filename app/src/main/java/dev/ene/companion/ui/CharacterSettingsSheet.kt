package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ene.companion.character.*
import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

data class CharacterSettingField(val key: String, val label: String, val section: String,
    val kind: String = "bool", val minimum: Double = 0.0, val maximum: Double = 1.0, val unit: String = "") {
    fun samples(): List<JsonPrimitive> = when (kind) {
        "bool" -> listOf(JsonPrimitive(true), JsonPrimitive(false))
        "expression" -> listOf(JsonPrimitive(""))
        "frequency" -> listOf("low", "normal", "high").map(::JsonPrimitive)
        else -> listOf(characterSliderValue(this, 0f), characterSliderValue(this, 1f))
    }
}

/** 화면에 표시할 키도 전송 허용 목록에 고정한다. 임의 PC 설정을 자동 노출하지 않는다. */
val characterSettingFields = listOf(
    CharacterSettingField("enable_builtin_idle_motion", "모델 기본 대기 동작", "기본 움직임"),
    CharacterSettingField("enable_auto_eye_blink", "자동 눈 깜빡임", "기본 움직임"),
    CharacterSettingField("enable_idle_motion", "추가 대기 움직임", "기본 움직임"),
    CharacterSettingField("idle_motion_strength", "대기 움직임 강도", "기본 움직임", "number", .2, 2.0),
    CharacterSettingField("idle_motion_speed", "대기 움직임 속도", "기본 움직임", "number", .5, 2.0),
    CharacterSettingField("enable_expressive_motion", "표현 움직임", "표현과 몸짓"),
    CharacterSettingField("expressive_motion_strength", "표현 움직임 강도", "표현과 몸짓", "number", .2, 2.5),
    CharacterSettingField("expressive_motion_speed", "표현 움직임 속도", "표현과 몸짓", "number", .4, 2.0),
    CharacterSettingField("expressive_motion_speech_boost", "말할 때 움직임 강조", "표현과 몸짓", "number", 0.0, 2.5),
    CharacterSettingField("enable_expressive_pose_transitions", "표현 자세 전환", "표현과 몸짓"),
    CharacterSettingField("enable_idle_synthetic_gestures", "대기 중 합성 몸짓", "표현과 몸짓"),
    CharacterSettingField("synthetic_gesture_scale", "합성 몸짓 크기", "표현과 몸짓", "number", .5, 3.0),
    CharacterSettingField("idle_synthetic_gesture_frequency", "대기 몸짓 빈도", "표현과 몸짓", "frequency"),
    CharacterSettingField("enable_head_pat", "쓰다듬기 사용", "쓰다듬기"),
    CharacterSettingField("head_pat_strength", "쓰다듬기 강도", "쓰다듬기", "number", .5, 2.5),
    CharacterSettingField("head_pat_fade_in_ms", "쓰다듬기 시작 전환", "쓰다듬기", "integer", 50.0, 1000.0, "ms"),
    CharacterSettingField("head_pat_fade_out_ms", "쓰다듬기 종료 전환", "쓰다듬기", "integer", 50.0, 1200.0, "ms"),
    CharacterSettingField("head_pat_active_emotion_custom", "쓰다듬는 동안 표정", "쓰다듬기", "expression"),
    CharacterSettingField("head_pat_end_emotion_custom", "쓰다듬은 뒤 표정", "쓰다듬기", "expression"),
    CharacterSettingField("head_pat_end_emotion_duration_sec", "종료 표정 유지 시간", "쓰다듬기", "integer", 1.0, 30.0, "초"),
)

fun characterSliderValue(field: CharacterSettingField, fraction: Float): JsonPrimitive {
    val position = fraction.toDouble().coerceIn(0.0, 1.0)
    val value = (field.minimum * (1 - position) + field.maximum * position).coerceIn(field.minimum, field.maximum)
    return if (field.kind == "integer") JsonPrimitive(value.roundToLong())
        else JsonPrimitive((round(value * 100) / 100).coerceIn(field.minimum, field.maximum))
}

private fun fraction(value: Double, min: Double, max: Double): Float {
    if (min == max) return 0f
    val scale = maxOf(1.0, abs(min), abs(max))
    return ((value / scale - min / scale) / (max / scale - min / scale)).toFloat().coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSettingsSheet(state: CharacterSettingsViewState, onClose: () -> Unit,
    onPreview: (String, JsonElement, Boolean) -> Unit, onSubmit: () -> Unit) {
    val snapshot = state.snapshot
    val settings = snapshot?.json?.get("settings")?.jsonObject ?: JsonObject(emptyMap())
    val expressions = snapshot?.json?.get("expression_ids")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    var decorations by remember(snapshot?.modelVersion) { mutableStateOf(false) }
    val enabled = state.available && !state.busy
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
            Text("캐릭터 공통 설정", style = MaterialTheme.typography.titleLarge)
            Text("변경한 값은 PC에도 저장됩니다. 슬라이더는 손을 뗄 때 저장합니다.", style = MaterialTheme.typography.bodySmall)
            val status = when {
                state.busy -> "PC 저장 결과와 최신 값을 확인하고 있습니다."
                !state.available -> "캐릭터 준비가 끝나면 설정할 수 있습니다."
                state.result == "conflict" -> "PC에서 먼저 바뀐 최신 값으로 갱신했습니다. 확인 후 다시 조정해 주세요."
                state.result == "rejected" -> "PC가 변경을 저장하지 않았습니다. 최신 값을 확인해 주세요."
                state.result == "unknown" -> "이전 저장 응답을 확인하지 못해 현재 PC 값을 다시 불러왔습니다."
                state.result == "accepted" -> "PC에 저장된 최신 값입니다."
                else -> "미리보기 중 닫으면 저장되지 않은 조정만 취소됩니다."
            }
            Text(status, Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)) {
                characterSettingFields.groupBy { it.section }.forEach { (section, fields) ->
                    item("section:$section") { Text(section, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
                    items(fields.filter { it.key in settings }, key = { it.key }) { field ->
                        key(snapshot?.modelVersion, snapshot?.settingsRevision) {
                            CharacterSettingRow(field, settings.getValue(field.key).jsonPrimitive, expressions, enabled,
                                { onPreview(field.key, it, false) }, onSubmit)
                        }
                    }
                }
                item("decorations") {
                    TextButton(onClick = { decorations = !decorations }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (decorations) "모델 장식 접기" else "모델 장식 펼치기")
                    }
                    Text("표정·입 모양·신체 움직임에 쓰이는 항목은 보호됩니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (decorations) items(snapshot?.catalog.orEmpty().filter { isCharacterParameterEditable(it.id) }, key = { "param:${it.id}" }) { parameter ->
                    val value = snapshot?.parameters?.get(parameter.id) ?: parameter.initial
                    key(snapshot?.modelVersion, snapshot?.settingsRevision) { Column {
                        Text(parameter.id, style = MaterialTheme.typography.labelLarge)
                        Text(String.format(Locale.ROOT, "%.3g", value), style = MaterialTheme.typography.bodySmall)
                        Slider(value = fraction(value, parameter.minimum, parameter.maximum),
                            onValueChange = { position -> onPreview(parameter.id,
                                JsonPrimitive((parameter.minimum * (1 - position.toDouble()) + parameter.maximum * position.toDouble())
                                    .coerceIn(parameter.minimum, parameter.maximum)), true) },
                            onValueChangeFinished = onSubmit, enabled = enabled && parameter.minimum < parameter.maximum,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "모델 장식 ${parameter.id}" })
                        TextButton(onClick = { onPreview(parameter.id, JsonNull, true); onSubmit() },
                            enabled = enabled && snapshot?.parameters?.containsKey(parameter.id) == true,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("${parameter.id} 기본값 복원") }
                    } }
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("닫기") }
        }
    }
}

@Composable
fun CharacterSettingRow(field: CharacterSettingField, value: JsonPrimitive, expressions: List<String>,
    enabled: Boolean, onPreview: (JsonPrimitive) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(field.label, style = MaterialTheme.typography.labelLarge)
        when (field.kind) {
            "bool" -> Switch(checked = value.boolean, enabled = enabled,
                onCheckedChange = { onPreview(JsonPrimitive(it)); onSubmit() },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = field.label })
            "expression", "frequency" -> {
                var expanded by remember { mutableStateOf(false) }
                val choices = if (field.kind == "expression") listOf("" to "PC 기본값") + expressions.map { it to it }
                    else listOf("low" to "낮음", "normal" to "보통", "high" to "높음")
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = field.label }) {
                        Text(choices.firstOrNull { it.first == value.content }?.second ?: "PC 기본값")
                    }
                    DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) },
                            onClick = { expanded = false; onPreview(JsonPrimitive(id)); onSubmit() }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                }
            }
            else -> {
                Text("${if (field.kind == "integer") value.long.toString() else String.format(Locale.ROOT, "%.2f", value.double)} ${field.unit}".trim(),
                    style = MaterialTheme.typography.bodySmall)
                Slider(value = fraction(value.double, field.minimum, field.maximum),
                    onValueChange = { onPreview(characterSliderValue(field, it)) }, onValueChangeFinished = onSubmit,
                    enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = field.label })
            }
        }
    }
}
