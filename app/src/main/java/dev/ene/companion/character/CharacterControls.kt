package dev.ene.companion.character

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import java.util.UUID

/** PC 장식 파라미터 창이 자동 표정/립싱크 충돌 방지를 위해 보호하는 항목이다. */
fun isCharacterParameterEditable(id: String): Boolean = listOf(
    "ParamEye", "ParamMouth", "ParamJaw", "ParamTongue", "ParamBrow", "ParamAngle",
    "ParamBody", "ParamBreath", "ParamArm", "ParamHand", "ParamShoulder", "ParamLeg",
).none(id::startsWith)

/** Main 전용 편집 기준. 전송한 명령을 보관해 재전송하는 큐는 만들지 않는다. */
class CharacterControls(private val commandId: () -> String = { UUID.randomUUID().toString() }) {
    var baseline: CharacterSnapshot? = null
        private set
    var phase = "idle"
        private set
    var lastResult: String? = null
        private set
    private var pending: CharacterSettingsPatch? = null
    private var minimumRevision = 0L

    fun begin(snapshot: CharacterSnapshot) {
        require(phase !in setOf("sending", "refreshing")) { "character_settings_busy" }
        require(snapshot.status == "ready" && snapshot.modelVersion != null) { "character_unavailable" }
        baseline = snapshot; minimumRevision = snapshot.settingsRevision
        phase = "editing"; lastResult = null
    }

    fun validate(changes: JsonObject, parameters: JsonObject): JsonObject {
        require(phase == "editing") { "character_settings_busy" }
        val current = baseline ?: throw CharacterException("character_unavailable")
        val clean = ExtensionCodec.normalizeSettings(changes)
        val expressions = current.json.getValue("expression_ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
        for (key in setOf("head_pat_active_emotion_custom", "head_pat_end_emotion_custom")) {
            val value = clean[key]?.jsonPrimitive?.content ?: continue
            require(value.isEmpty() || value in expressions) { "invalid_expression" }
        }
        require(parameters.size <= 256) { "invalid_parameters" }
        val catalog = current.catalog.associateBy { it.id }
        for ((key, value) in parameters) {
            require(isCharacterParameterEditable(key)) { "read_only_parameter" }
            val bound = catalog[key] ?: throw CharacterException("invalid_parameters")
            if (value == JsonNull) continue
            val item = value as? JsonPrimitive ?: throw CharacterException("invalid_parameters")
            val number = item.doubleOrNull
            require(!item.isString && number != null && number.isFinite() && number in bound.minimum..bound.maximum) { "invalid_parameters" }
        }
        return clean
    }

    fun submit(context: ExtensionContext, changes: JsonObject, parameters: JsonObject): CharacterSettingsPatch {
        val clean = validate(changes, parameters)
        val current = requireNotNull(baseline)
        val patch = CharacterSettingsPatch(context.registrationGeneration, context.serverEpoch, context.connectionGeneration,
            current.modelVersion!!, commandId(), current.settingsRevision, clean, JsonObject(parameters.toMap()))
        // 전체 전송 크기와 UUID도 보내기 전에 검사한다.
        ProtocolCodec.encode(patch)
        pending = patch; phase = "sending"; lastResult = null
        return patch
    }

    fun receive(result: CharacterSettingsResult): Boolean {
        val request = pending ?: return false
        if (phase != "sending" || result.command_id != request.command_id ||
            result.registration_generation != request.registration_generation ||
            result.server_epoch != request.server_epoch || result.connection_generation != request.connection_generation ||
            result.status !in setOf("accepted", "conflict", "rejected") ||
            result.settings_revision < request.expected_revision + if (result.status == "accepted") 1 else 0) return false
        minimumRevision = result.settings_revision
        pending = null; phase = "refreshing"; lastResult = result.status
        return true
    }

    fun deliveryUnknown() {
        if (phase != "sending") return
        pending = null; phase = "refreshing"; lastResult = "unknown"
    }

    fun refresh(snapshot: CharacterSnapshot): Boolean {
        if (phase != "refreshing" || snapshot.status != "ready" || snapshot.settingsRevision < minimumRevision) return false
        baseline = snapshot; minimumRevision = snapshot.settingsRevision; phase = "editing"
        return true
    }

    fun reset() {
        baseline = null; pending = null; minimumRevision = 0; phase = "idle"; lastResult = null
    }
}
