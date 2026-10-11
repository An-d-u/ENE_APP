package dev.ene.companion.protocol

import kotlinx.serialization.json.*

/** 기본 메시지와 크기 상한을 분리한 사진·PCM 확장 구문 검사. */
internal object InputCodec {
    private val uploads = setOf("input_reserve", "input_reserved", "input_cancel")
    private val images = setOf("chat_images_send", "chat_images_request", "chat_images_state",
        "chat_images_invalidated", "chat_images_limits_request", "chat_images_limits")
    private val stt = setOf("stt_limits_request", "stt_limits", "stt_begin", "stt_ready", "stt_transcribe", "stt_result", "stt_cancel")
    val types = uploads + images + stt
    val fromPhone = setOf("input_reserve", "input_cancel", "chat_images_send", "chat_images_request", "chat_images_limits_request",
        "stt_limits_request", "stt_begin", "stt_transcribe", "stt_cancel")
    fun feature(kind: String, body: JsonObject) = when {
        kind in images -> "chat_images_v1"
        kind in stt -> "stt_input_v1"
        body["purpose"]?.jsonPrimitive?.content == "image" -> "chat_images_v1"
        else -> "stt_input_v1"
    }
    private fun choice(value: JsonElement?, choices: Set<String>) = ProtocolCodec.text(value).also {
        if (it !in choices) throw ProtocolException()
    }
    private fun digest(value: JsonElement?) = ProtocolCodec.text(value).also {
        if (!Regex("[a-f0-9]{64}").matches(it)) throw ProtocolException()
    }
    private fun code(value: JsonElement?) = ProtocolCodec.text(value).also {
        if (!Regex("[a-z][a-z0-9_]{0,63}").matches(it)) throw ProtocolException()
    }
    private fun array(value: JsonElement?, minimum: Int): JsonArray = (value as? JsonArray ?: throw ProtocolException()).also {
        if (it.size !in minimum..4) throw ProtocolException()
    }
    private fun obj(value: JsonElement) = value as? JsonObject ?: throw ProtocolException()

    fun normalize(kind: String, body: JsonObject) = buildJsonObject {
        put("conversation_id", ProtocolCodec.uuid(body["conversation_id"]))
        if (kind in uploads + setOf("stt_begin", "stt_ready", "stt_transcribe", "stt_result", "stt_cancel"))
            put("command_id", ProtocolCodec.uuid(body["command_id"]))
        if (kind in setOf("chat_images_request", "chat_images_state", "chat_images_limits_request", "chat_images_limits", "stt_limits_request", "stt_limits"))
            put("query_id", ProtocolCodec.uuid(body["query_id"]))
        when (kind) {
            in uploads -> {
                val purpose = choice(body["purpose"], setOf("image", "pcm"))
                put("purpose", purpose)
                if (kind == "input_reserve") {
                    val length = ProtocolCodec.integer(body["byte_length"], 1, if (purpose == "image") 4194304 else 960000)
                    if (purpose == "pcm" && length % 2 != 0L) throw ProtocolException()
                    put("byte_length", length); put("sha256", digest(body["sha256"]))
                } else put("upload_id", ProtocolCodec.uuid(body["upload_id"]))
            }
            "chat_images_send" -> {
                put("request_id", ProtocolCodec.uuid(body["request_id"]))
                put("text", ProtocolCodec.text(body["text"], ProtocolCodec.MAX_TEXT_BYTES))
                val values = array(body["images"], 1).map { value ->
                    val item = obj(value)
                    buildJsonObject {
                        put("upload_id", ProtocolCodec.uuid(item["upload_id"])); put("sha256", digest(item["sha256"]))
                        put("byte_length", ProtocolCodec.integer(item["byte_length"], 1, 4194304))
                        put("mime", choice(item["mime"], setOf("image/png", "image/jpeg")))
                    }
                }
                if (values.map { it["upload_id"] }.distinct().size != values.size) throw ProtocolException()
                put("images", JsonArray(values))
            }
            "chat_images_request", "chat_images_state", "chat_images_invalidated" -> {
                put("message_id", ProtocolCodec.uuid(body["message_id"]))
                if (kind != "chat_images_invalidated") put("conversation_revision", ProtocolCodec.integer(body["conversation_revision"]))
                if (kind != "chat_images_request") put("attachment_revision", ProtocolCodec.integer(body["attachment_revision"]))
                if (kind == "chat_images_state") {
                    val values = array(body["images"], 0).map { value ->
                        val item = obj(value)
                        buildJsonObject {
                            put("id", ProtocolCodec.uuid(item["id"])); put("width", ProtocolCodec.integer(item["width"], 1, 2048))
                            put("height", ProtocolCodec.integer(item["height"], 1, 2048))
                            put("mime", choice(item["mime"], setOf("image/png", "image/jpeg")))
                        }
                    }
                    if (values.map { it["id"] }.distinct().size != values.size) throw ProtocolException()
                    put("images", JsonArray(values))
                }
            }
            "stt_limits", "stt_ready" -> {
                val minimum = ProtocolCodec.integer(body["min_ms"], 0, 30000)
                put("min_ms", minimum); put("max_ms", ProtocolCodec.integer(body["max_ms"], maxOf(1, minimum), 30000))
                if (kind == "stt_limits") for ((name, value) in mapOf("sample_rate" to 16000L, "channels" to 1L, "sample_width" to 2L))
                    put(name, ProtocolCodec.integer(body[name], value, value))
            }
            "stt_transcribe" -> {
                put("upload_id", ProtocolCodec.uuid(body["upload_id"])); put("sha256", digest(body["sha256"]))
                val length = ProtocolCodec.integer(body["byte_length"], 2, 960000)
                if (length % 2 != 0L) throw ProtocolException()
                put("byte_length", length)
            }
            "stt_result" -> {
                val status = choice(body["status"], setOf("complete", "failed", "cancelled"))
                val text = ProtocolCodec.text(body["text"], ProtocolCodec.MAX_TEXT_BYTES)
                if ((status == "complete" && text.isBlank()) || (status != "complete" && text.isNotEmpty())) throw ProtocolException()
                put("status", status); put("text", text); put("reason", code(body["reason"]))
            }
        }
        if (kind in setOf("chat_images_limits", "stt_limits")) {
            val value = body["allowed"] as? JsonPrimitive ?: throw ProtocolException()
            if (value.isString) throw ProtocolException()
            val allowed = value.booleanOrNull ?: throw ProtocolException()
            val reason = code(body["reason"])
            if (allowed != (reason == "ready")) throw ProtocolException()
            put("allowed", allowed); put("reason", reason)
            if (kind == "chat_images_limits") put("max_count", ProtocolCodec.integer(body["max_count"], 4, 4))
        }
    }
}
