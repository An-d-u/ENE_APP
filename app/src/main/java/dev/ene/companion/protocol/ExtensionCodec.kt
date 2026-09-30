package dev.ene.companion.protocol

import kotlinx.serialization.json.*

/** 구문 검사는 순수 함수이며 실제 작업·현재 모델 검사는 해당 조정기가 소유한다. */
object ExtensionCodec {
    val capabilities = setOf("audio_pcm_v1", "character_v1", "character_controls_v1", "chat_actions_v1")
    private val chatTypes = setOf("chat_actions_request", "chat_actions_state", "chat_action")
    private val chatReasons = setOf("ready", "no_target", "busy", "ai_unavailable", "unsupported_command", "text_too_large")
    private val audioRefs = listOf("conversation_id", "message_id", "operation_id", "utterance_id")
    private val audioTypes = setOf("audio_availability", "audio_status", "audio_offer", "audio_prepared", "audio_rejected", "audio_start",
        "audio_started", "audio_source_end", "audio_progress", "audio_progress_ack", "audio_finished", "audio_cancel")
    private val characterTypes = setOf("character_changed", "character_snapshot_request", "character_action", "character_playback")
    private val controlTypes = setOf("head_pat", "head_pat_state", "character_settings_patch", "character_settings_result")
    val types = audioTypes + characterTypes + controlTypes + chatTypes + setOf("extensions_ready", "extension_error")
    private val fromPhone = setOf("audio_availability", "audio_prepared", "audio_rejected", "audio_started", "audio_progress",
        "audio_finished", "audio_cancel", "character_snapshot_request", "head_pat", "character_settings_patch", "chat_actions_request", "chat_action")
    private val fromPc = types - fromPhone + "audio_cancel"
    private val smallTypes = setOf("audio_progress", "audio_progress_ack", "character_playback", "head_pat", "head_pat_state", "chat_actions_request", "chat_actions_state")
    private val boolSettings = setOf("enable_builtin_idle_motion", "enable_auto_eye_blink", "enable_idle_motion",
        "enable_expressive_motion", "enable_expressive_pose_transitions", "enable_idle_synthetic_gestures", "enable_head_pat")
    private val numberSettings = mapOf("idle_motion_strength" to (0.2 to 2.0), "idle_motion_speed" to (0.5 to 2.0),
        "expressive_motion_strength" to (0.2 to 2.5), "expressive_motion_speed" to (0.4 to 2.0),
        "expressive_motion_speech_boost" to (0.0 to 2.5), "synthetic_gesture_scale" to (0.5 to 3.0), "head_pat_strength" to (0.5 to 2.5))
    private val intSettings = mapOf("head_pat_fade_in_ms" to (50L to 1000L), "head_pat_fade_out_ms" to (50L to 1200L),
        "head_pat_end_emotion_duration_sec" to (1L to 30L))
    private val expressionSettings = setOf("head_pat_active_emotion_custom", "head_pat_end_emotion_custom")

    fun wireLimit(kind: String): Int = if (kind in smallTypes) 2048 else if (kind == "character_settings_patch") 49152 else 65536

    fun negotiate(offered: Set<String>, supported: Set<String> = emptySet()): Set<String> {
        val common = (offered intersect supported intersect capabilities).toMutableSet()
        if ("character_v1" !in common) common.remove("character_controls_v1")
        return common.toSet()
    }

    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: throw ProtocolException()
    private fun choice(value: JsonElement?, choices: Set<String>): String = ProtocolCodec.text(value).also {
        if (it !in choices) throw ProtocolException()
    }
    private fun bool(value: JsonElement?): Boolean {
        val item = value as? JsonPrimitive ?: throw ProtocolException()
        if (item.isString) throw ProtocolException()
        return item.booleanOrNull ?: throw ProtocolException()
    }
    private fun number(value: JsonElement?, min: Double = Double.NEGATIVE_INFINITY, max: Double = Double.POSITIVE_INFINITY): JsonPrimitive {
        val item = value as? JsonPrimitive ?: throw ProtocolException()
        val numeric = item.doubleOrNull
        if (item.isString || numeric == null || !numeric.isFinite() || numeric < min || numeric > max) throw ProtocolException()
        return item
    }
    private fun code(value: JsonElement?): String = ProtocolCodec.text(value).also {
        if (!Regex("[a-z][a-z0-9_]{0,63}").matches(it)) throw ProtocolException()
    }
    private fun digest(value: JsonElement?, nullable: Boolean = false): JsonElement {
        if (nullable && value == JsonNull) return JsonNull
        val text = ProtocolCodec.text(value)
        if (!Regex("[0-9a-f]{64}").matches(text)) throw ProtocolException()
        return JsonPrimitive(text)
    }

    fun normalizeSettings(value: JsonElement?): JsonObject = buildJsonObject {
        for ((key, item) in obj(value)) {
            when (key) {
                in boolSettings -> put(key, bool(item))
                in numberSettings -> { val range = numberSettings.getValue(key); put(key, number(item, range.first, range.second)) }
                in intSettings -> { val range = intSettings.getValue(key); put(key, ProtocolCodec.integer(item, range.first, range.second)) }
                in expressionSettings -> put(key, ProtocolCodec.text(item, 128))
                "idle_synthetic_gesture_frequency" -> put(key, choice(item, setOf("low", "normal", "high")))
                else -> throw ProtocolException()
            }
        }
    }

    internal fun normalize(kind: String, body: JsonObject): JsonObject = buildJsonObject {
        put("type", kind)
        put("protocol_version", 1)
        put("registration_generation", ProtocolCodec.integer(body["registration_generation"], 1))
        put("server_epoch", ProtocolCodec.uuid(body["server_epoch"]))
        put("connection_generation", ProtocolCodec.uuid(body["connection_generation"]))
        if (kind in audioTypes - setOf("audio_availability", "audio_status")) {
            for (key in audioRefs) put(key, ProtocolCodec.uuid(body[key]))
        }
        if (kind in setOf("head_pat", "head_pat_state", "character_action", "character_settings_patch")) {
            put("model_version", digest(body["model_version"]))
        }
        if (kind in setOf("audio_rejected", "audio_cancel", "audio_status", "audio_availability", "character_changed",
                "head_pat_state", "character_settings_result")) put("reason", code(body["reason"]))
        when (kind) {
            "extensions_ready" -> {
                val offered = body["capabilities"] as? JsonArray ?: throw ProtocolException()
                if (offered.size !in 1..capabilities.size) throw ProtocolException()
                val values = offered.map { choice(it, capabilities) }
                if (values.distinct().size != values.size || negotiate(values.toSet(), capabilities) != values.toSet()) throw ProtocolException()
                put("capabilities", JsonArray(values.map(::JsonPrimitive)))
            }
            in chatTypes -> {
                put("conversation_id", ProtocolCodec.uuid(body["conversation_id"]))
                when (kind) {
                    "chat_actions_request" -> {
                        put("query_id", ProtocolCodec.uuid(body["query_id"]))
                        put("refresh", bool(body["refresh"]))
                    }
                    "chat_action" -> {
                        val action = choice(body["kind"], setOf("edit", "reroll"))
                        put("request_id", ProtocolCodec.uuid(body["request_id"]))
                        put("kind", action)
                        put("target_message_id", ProtocolCodec.uuid(body["target_message_id"]))
                        put("expected_revision", ProtocolCodec.integer(body["expected_revision"]))
                        if (action == "edit") put("text", ProtocolCodec.text(body["text"], ProtocolCodec.MAX_TEXT_BYTES, nonblank = true, limitCode = "text_too_large"))
                        else if ("text" in body) throw ProtocolException()
                    }
                    else -> {
                        for (key in listOf("query_id", "snapshot_id", "user_message_id", "assistant_message_id")) {
                            put(key, if (body[key] == null || body[key] == JsonNull) JsonNull else JsonPrimitive(ProtocolCodec.uuid(body[key])))
                        }
                        for (key in listOf("conversation_revision", "event_seq", "state_seq")) {
                            put(key, ProtocolCodec.integer(body[key], if (key == "state_seq") 1 else 0))
                        }
                        for ((action, target) in listOf("edit" to "user_message_id", "reroll" to "assistant_message_id")) {
                            val allowed = bool(body[action + "_allowed"])
                            val reason = choice(body[action + "_reason"], chatReasons)
                            if (allowed != (reason == "ready") || (allowed && (body[target] == null || body[target] == JsonNull))) throw ProtocolException()
                            put(action + "_allowed", allowed); put(action + "_reason", reason)
                        }
                        if (body["snapshot_id"] != null && body["snapshot_id"] != JsonNull &&
                            (body["query_id"] == null || body["query_id"] == JsonNull)) throw ProtocolException()
                    }
                }
            }
            "audio_availability" -> {
                put("available", bool(body["available"]))
                put("conversation_id", ProtocolCodec.uuid(body["conversation_id"]))
            }
            "audio_status" -> {
                put("mode", choice(body["mode"], setOf("disabled", "pc_only", "auto")))
                for ((key, values) in mapOf("preference" to setOf("auto", "pc", "phone"),
                    "output" to setOf("none", "pc", "phone"), "state" to setOf("idle", "preparing", "playing", "stopped"))) {
                    if (key in body) put(key, choice(body[key], values))
                }
            }
            "audio_offer" -> {
                put("sample_rate", ProtocolCodec.integer(body["sample_rate"], 8000, 48000))
                put("channels", ProtocolCodec.integer(body["channels"], 1, 2))
                put("sample_width", ProtocolCodec.integer(body["sample_width"], 2, 2))
                put("prepare_timeout_ms", ProtocolCodec.integer(body["prepare_timeout_ms"], 2000, 2000))
            }
            "audio_prepared" -> put("buffered_frames", ProtocolCodec.integer(body["buffered_frames"], 0, 48000L * 4))
            "audio_started", "audio_progress", "audio_progress_ack", "audio_finished" -> {
                put("played_frames", ProtocolCodec.integer(body["played_frames"], 0, if (kind == "audio_started") 0 else 48000L * 180))
                if (kind == "audio_progress") put("mouth_open", number(body["mouth_open"], 0.0, 1.0))
            }
            "audio_source_end" -> put("total_frames", ProtocolCodec.integer(body["total_frames"], 0, 48000L * 180))
            "character_changed" -> {
                put("state_revision", ProtocolCodec.integer(body["state_revision"], 1))
                put("model_version", digest(body["model_version"], nullable = true))
            }
            "character_action" -> {
                put("action_seq", ProtocolCodec.integer(body["action_seq"], 1))
                put("kind", choice(body["kind"], setOf("expression", "gesture")))
                put("action_id", ProtocolCodec.text(body["action_id"], 128, nonblank = true))
                put("duration_ms", ProtocolCodec.integer(body["duration_ms"], 0, 30000))
            }
            "character_playback" -> {
                for (key in listOf("conversation_id", "message_id", "utterance_id")) put(key, ProtocolCodec.uuid(body[key]))
                put("output", choice(body["output"], setOf("pc", "phone", "none")))
                put("played_ms", ProtocolCodec.integer(body["played_ms"], 0, 180000))
                put("mouth_open", number(body["mouth_open"], 0.0, 1.0))
                put("active", bool(body["active"]))
            }
            "head_pat", "head_pat_state" -> {
                put("interaction_id", ProtocolCodec.uuid(body["interaction_id"]))
                put("interaction_no", ProtocolCodec.integer(body["interaction_no"], 1))
                put("seq", ProtocolCodec.integer(body["seq"]))
                put("intensity", number(body["intensity"], 0.0, 1.0))
                put("phase", choice(body["phase"], if (kind == "head_pat") setOf("start", "update", "end", "cancel")
                    else setOf("accepted", "update", "ended", "cancelled", "rejected")))
                if (kind == "head_pat_state") put("source", choice(body["source"], setOf("pc", "phone")))
            }
            "character_settings_patch" -> {
                val parameters = obj(body["parameters"])
                if (parameters.size > 256) throw ProtocolException()
                put("command_id", ProtocolCodec.uuid(body["command_id"]))
                put("expected_revision", ProtocolCodec.integer(body["expected_revision"]))
                put("changes", normalizeSettings(body["changes"]))
                put("parameters", buildJsonObject {
                    for ((key, value) in parameters) {
                        ProtocolCodec.text(JsonPrimitive(key), 128, nonblank = true)
                        put(key, if (value == JsonNull) JsonNull else number(value))
                    }
                })
            }
            "character_settings_result" -> {
                put("command_id", ProtocolCodec.uuid(body["command_id"]))
                put("status", choice(body["status"], setOf("accepted", "conflict", "rejected")))
                put("settings_revision", ProtocolCodec.integer(body["settings_revision"]))
            }
            "extension_error" -> {
                put("feature", choice(body["feature"], capabilities))
                put("code", code(body["code"]))
                if ("command_id" in body) put("command_id", ProtocolCodec.uuid(body["command_id"]))
            }
            "audio_rejected", "audio_cancel", "audio_start", "character_snapshot_request" -> Unit
            else -> throw ProtocolException("unsupported_command")
        }
    }

    fun validate(message: ExtensionMessage, context: ExtensionContext, negotiatedCapabilities: Set<String>,
                 direction: String, sampleRate: Int? = null) {
        val body = Json.parseToJsonElement(ProtocolCodec.encode(message)).jsonObject
        val kind = body.getValue("type").jsonPrimitive.content
        if (message.registration_generation != context.registrationGeneration || message.server_epoch != context.serverEpoch ||
            message.connection_generation != context.connectionGeneration ||
            ("conversation_id" in body && body.getValue("conversation_id").jsonPrimitive.content != context.conversationId)) {
            throw ProtocolException("stale_extension")
        }
        val negotiated = negotiate(negotiatedCapabilities, capabilities)
        val feature = when (kind) {
            in chatTypes -> "chat_actions_v1"
            in audioTypes -> "audio_pcm_v1"
            in characterTypes -> "character_v1"
            in controlTypes -> "character_controls_v1"
            "extension_error" -> body.getValue("feature").jsonPrimitive.content
            else -> null
        }
        if ((feature != null && feature !in negotiated) || (kind == "extensions_ready" &&
                (negotiated.isEmpty() || body.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet() != negotiated))) {
            throw ProtocolException("extension_not_negotiated")
        }
        if (direction !in setOf("from_pc", "from_phone") || kind !in (if (direction == "from_phone") fromPhone else fromPc)) {
            throw ProtocolException("unsupported_command")
        }
        if (sampleRate != null) {
            if (sampleRate !in 8000..48000) throw ProtocolException()
            for ((key, seconds) in listOf("buffered_frames" to 4, "played_frames" to 180, "total_frames" to 180)) {
                if (key in body && body.getValue(key).jsonPrimitive.long > sampleRate.toLong() * seconds) throw ProtocolException()
            }
        }
    }
}
