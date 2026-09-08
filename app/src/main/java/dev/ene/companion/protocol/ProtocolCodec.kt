package dev.ene.companion.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Collections
import java.util.UUID

class ProtocolException(val code: String = "invalid_message") : IllegalArgumentException(code)

/** 라이브러리의 느슨한 숫자 변환 전에 양쪽 계약의 자료형을 검사한다. */
object ProtocolCodec {
    const val MAX_WIRE_BYTES = 65536
    const val MAX_TEXT_BYTES = 16384
    const val MAX_PUBLIC_TEXT_BYTES = 1048576
    const val MAX_SNAPSHOT_BYTES = 33554432
    const val MAX_MESSAGES = 50000
    const val PART_BYTES = 32768
    const val MAX_PARTS = MAX_SNAPSHOT_BYTES / PART_BYTES
    private const val MAX_SAFE_INTEGER = 9007199254740991L
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val codePattern = Regex("[a-z][a-z0-9_]{0,63}")
    private val capabilityPattern = Regex("[a-z][a-z0-9_.:-]{0,63}")
    private val utcPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private inline fun <T> safe(block: () -> T): T = try {
        block()
    } catch (error: ProtocolException) {
        throw error
    } catch (_: IllegalArgumentException) {
        throw ProtocolException()
    } catch (_: java.time.DateTimeException) {
        throw ProtocolException()
    }

    fun decode(raw: String): WireMessage = safe {
        checkedString(raw, MAX_WIRE_BYTES, limitCode = "message_too_large")
        checkDepth(raw)
        json.decodeFromJsonElement<WireMessage>(normalize(obj(json.parseToJsonElement(raw))))
    }

    fun encode(message: WireMessage): String = safe {
        val normalized = normalize(obj(json.parseToJsonElement(json.encodeToString<WireMessage>(message))))
        checkedString(normalized.toString(), MAX_WIRE_BYTES, limitCode = "message_too_large")
    }

    /** QR·서버 정보에도 동일한 UTF-8·깊이·객체 검사를 적용한다. */
    internal fun readObject(raw: String, maxBytes: Int): JsonObject = safe {
        checkedString(raw, maxBytes)
        checkDepth(raw)
        obj(json.parseToJsonElement(raw))
    }

    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: throw ProtocolException()

    private fun checkedString(value: String, maxBytes: Int? = null, nonblank: Boolean = false, limitCode: String = "invalid_message"): String {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) throw ProtocolException()
                index += 2
            } else {
                if (char.isLowSurrogate()) throw ProtocolException()
                index++
            }
        }
        if (maxBytes != null && value.toByteArray(Charsets.UTF_8).size > maxBytes) throw ProtocolException(limitCode)
        // Python의 공백 판정과 동일하게 NEXT LINE 코드 포인트도 빈 입력으로 본다.
        if (nonblank && value.all { it.isWhitespace() || it == '\u0085' }) throw ProtocolException()
        return value
    }

    internal fun text(value: JsonElement?, maxBytes: Int? = null, nonblank: Boolean = false, limitCode: String = "invalid_message"): String {
        val primitive = value as? JsonPrimitive ?: throw ProtocolException()
        if (!primitive.isString) throw ProtocolException()
        return checkedString(primitive.content, maxBytes, nonblank, limitCode)
    }

    internal fun uuid(value: JsonElement?): String {
        val value = text(value)
        if (!uuidPattern.matches(value)) throw ProtocolException()
        return UUID.fromString(value).toString()
    }

    internal fun integer(value: JsonElement?, minimum: Long = 0, maximum: Long = MAX_SAFE_INTEGER): Long {
        val primitive = value as? JsonPrimitive ?: throw ProtocolException()
        if (primitive.isString || !Regex("-?(0|[1-9][0-9]*)").matches(primitive.content)) throw ProtocolException()
        val number = primitive.content.toLongOrNull() ?: throw ProtocolException()
        if (number !in minimum..maximum) throw ProtocolException()
        return number
    }

    private fun symbol(value: JsonElement?, pattern: Regex = codePattern): String {
        val value = text(value)
        if (!pattern.matches(value)) throw ProtocolException()
        return value
    }

    private fun choice(value: JsonElement?, vararg choices: String): String = text(value).also {
        if (it !in choices) throw ProtocolException()
    }

    private fun list(value: JsonElement?, maximum: Int, transform: (JsonElement) -> String): JsonArray {
        val values = value as? JsonArray ?: throw ProtocolException()
        if (values.size > maximum) throw ProtocolException()
        val result = values.map(transform)
        if (result.distinct().size != result.size) throw ProtocolException()
        return JsonArray(result.map(::JsonPrimitive))
    }

    internal fun credential(value: JsonElement?): String {
        val value = text(value)
        if (!Regex("[A-Za-z0-9_-]{43}").matches(value)) throw ProtocolException()
        val decoded = Base64.getUrlDecoder().decode(value)
        if (decoded.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != value) throw ProtocolException()
        return value
    }

    internal fun utc(value: JsonElement?): String {
        val value = text(value)
        if (!utcPattern.matches(value) || value.startsWith("0000")) throw ProtocolException()
        OffsetDateTime.parse(value)
        return value
    }

    private fun publicMessage(value: JsonElement?): JsonObject {
        val body = obj(value)
        return buildJsonObject {
            put("id", uuid(body["id"]))
            put("role", choice(body["role"], "user", "assistant"))
            put("text", text(body["text"], MAX_PUBLIC_TEXT_BYTES))
            put("displayed_at", utc(body["displayed_at"]))
            if (body.containsKey("request_id")) put("request_id", uuid(body["request_id"]))
            if (body.containsKey("attachment_unsupported")) {
                val marker = body["attachment_unsupported"] as? JsonPrimitive ?: throw ProtocolException()
                if (marker.isString || marker.booleanOrNull != true) throw ProtocolException()
                put("attachment_unsupported", true)
            }
        }
    }

    private fun processing(value: JsonElement?): JsonObject {
        val body = obj(value)
        val phase = choice(body["phase"], "idle", "preparing", "responding")
        return buildJsonObject {
            put("phase", phase)
            if (body.containsKey("request_id")) {
                if (phase == "idle") throw ProtocolException()
                put("request_id", uuid(body["request_id"]))
            }
        }
    }

    fun decodePublicMessage(value: JsonObject): PublicMessage = safe { json.decodeFromJsonElement(publicMessage(value)) }
    fun decodeProcessing(value: JsonObject): ProcessingState = safe { json.decodeFromJsonElement(processing(value)) }

    fun decodePart(value: String): ByteArray = safe {
        val decoded = Base64.getDecoder().decode(value)
        if (decoded.isEmpty() || decoded.size > PART_BYTES || Base64.getEncoder().encodeToString(decoded) != value) throw ProtocolException()
        decoded
    }

    fun decodeSnapshot(bytes: ByteArray): Pair<List<PublicMessage>, ProcessingState> = safe {
        if (bytes.size > MAX_SNAPSHOT_BYTES) throw ProtocolException("snapshot_too_large")
        val raw = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ProtocolException("invalid_snapshot")
        }
        checkDepth(raw)
        val body = obj(json.parseToJsonElement(raw))
        val messages = body["messages"] as? JsonArray ?: throw ProtocolException("invalid_snapshot")
        if (messages.size > MAX_MESSAGES) throw ProtocolException("snapshot_too_large")
        val result = messages.map { json.decodeFromJsonElement<PublicMessage>(publicMessage(it)) }
        if (result.map { it.id }.distinct().size != result.size) throw ProtocolException("invalid_snapshot")
        Collections.unmodifiableList(result) to json.decodeFromJsonElement<ProcessingState>(processing(body["processing"]))
    }

    private fun normalize(body: JsonObject): JsonObject {
        if (integer(body["protocol_version"]) != 1L) throw ProtocolException("unsupported_version")
        val kind = text(body["type"])
        return buildJsonObject {
            put("type", kind)
            put("protocol_version", 1)
            fun session() {
                put("server_epoch", uuid(body["server_epoch"]))
                put("conversation_id", uuid(body["conversation_id"]))
            }
            fun sizes() {
                val size = integer(body["byte_count"], 1, MAX_SNAPSHOT_BYTES.toLong())
                val parts = integer(body["part_count"], 1, MAX_PARTS.toLong())
                if (parts != (size + PART_BYTES - 1) / PART_BYTES) throw ProtocolException()
                val digest = text(body["sha256"])
                if (!Regex("[0-9a-f]{64}").matches(digest)) throw ProtocolException()
                put("part_count", parts)
                put("byte_count", size)
                put("sha256", digest)
            }
            when (kind) {
                "hello" -> put("capabilities", list(body["capabilities"] ?: JsonArray(emptyList()), 32) { symbol(it, capabilityPattern) })
                "ready" -> {
                    session()
                    put("server_id", uuid(body["server_id"]))
                    put("registration_generation", integer(body["registration_generation"], 1))
                    put("capabilities", list(body["capabilities"] ?: JsonArray(emptyList()), 32) { symbol(it, capabilityPattern) })
                }
                "sync_request" -> put("pending_request_ids", list(body["pending_request_ids"] ?: JsonArray(emptyList()), 1, ::uuid))
                "pair_request", "pair_pending", "pair_failed", "pair_approved" -> {
                    put("pairing_id", uuid(body["pairing_id"]))
                    when (kind) {
                        "pair_request" -> {
                            val name = text(body["device_name"], nonblank = true)
                            if (name.codePointCount(0, name.length) > 80) throw ProtocolException()
                            put("device_name", name)
                            put("secret", credential(body["secret"]))
                        }
                        "pair_failed" -> put("code", symbol(body["code"]))
                        "pair_approved" -> {
                            put("server_id", uuid(body["server_id"]))
                            put("device_id", uuid(body["device_id"]))
                            put("registration_generation", integer(body["registration_generation"], 1))
                            put("token", credential(body["token"]))
                        }
                    }
                }
                "snapshot_begin", "snapshot_part", "snapshot_end" -> {
                    session()
                    put("snapshot_id", uuid(body["snapshot_id"]))
                    if (kind == "snapshot_part") {
                        val encoded = text(body["data_base64"])
                        decodePart(encoded)
                        put("index", integer(body["index"], 0, (MAX_PARTS - 1).toLong()))
                        put("data_base64", encoded)
                    } else {
                        sizes()
                        if (kind == "snapshot_begin") {
                            put("event_seq", integer(body["event_seq"]))
                            put("conversation_revision", integer(body["conversation_revision"]))
                            put("message_count", integer(body["message_count"], 0, MAX_MESSAGES.toLong()))
                        }
                    }
                }
                "event" -> {
                    session()
                    put("event_seq", integer(body["event_seq"]))
                    put("conversation_revision", integer(body["conversation_revision"]))
                    val op = choice(body["op"], "append", "processing")
                    put("op", op)
                    put("payload", if (op == "append") publicMessage(body["payload"]) else processing(body["payload"]))
                }
                "resync_required" -> {
                    session()
                    put("reason", choice(body["reason"], "reset", "replace", "large_event", "gap"))
                }
                "send_text" -> {
                    session()
                    put("request_id", uuid(body["request_id"]))
                    put("text", text(body["text"], MAX_TEXT_BYTES, nonblank = true, limitCode = "text_too_large"))
                }
                "request_status" -> {
                    session()
                    put("request_id", uuid(body["request_id"]))
                    put("state", choice(body["state"], "unknown", "reserved", "accepted", "completed", "failed", "rejected"))
                    if (body.containsKey("message_id")) put("message_id", uuid(body["message_id"]))
                    if (body.containsKey("code")) put("code", symbol(body["code"]))
                }
                "ping", "pong" -> put("nonce", uuid(body["nonce"]))
                "error" -> {
                    put("code", symbol(body["code"]))
                    if (body.containsKey("request_id")) put("request_id", uuid(body["request_id"]))
                }
                else -> throw ProtocolException("unsupported_command")
            }
        }
    }

    private fun checkDepth(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '[', '{' -> { depth++; if (depth > 16) throw ProtocolException() }
                ']', '}' -> depth--
            }
        }
    }
}
