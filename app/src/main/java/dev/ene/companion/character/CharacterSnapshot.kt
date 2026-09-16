package dev.ene.companion.character

import dev.ene.companion.protocol.ExtensionCodec
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

class CharacterException(val code: String) : IllegalArgumentException(code)

data class CharacterAsset(val id: String, val sha256: String, val size: Long, val mime: String) {
    internal fun descriptor() = buildJsonObject {
        put("id", id); put("mime", mime); put("sha256", sha256); put("size", size)
    }
}

data class CharacterParameter(val id: String, val minimum: Double, val maximum: Double, val initial: Double)

/** 검증한 공개 필드만 유지한다. 원문이나 로컬 경로를 로그/진단 문자열에 넣지 않는다. */
class CharacterSnapshot private constructor(
    val json: JsonObject,
    val status: String,
    val modelVersion: String?,
    val entryAssetId: String?,
    val stateRevision: Long,
    val settingsRevision: Long,
    val actionSeq: Long,
    val assets: List<CharacterAsset>,
    val catalog: List<CharacterParameter>,
    val parameters: Map<String, Double>,
) {
    val totalBytes: Long get() = assets.sumOf { it.size }
    override fun toString() = "CharacterSnapshot(status=$status)"

    companion object {
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_ASSET_BYTES = 32L * 1024 * 1024
        const val MAX_MODEL_BYTES = 128L * 1024 * 1024
        const val RUNTIME_VERSION = 1
        private val digestPattern = Regex("[a-f0-9]{64}")
        private val mimeTypes = setOf("application/json", "application/octet-stream", "image/png", "image/jpeg")
        internal fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        internal fun digest(value: JsonElement?): String = ProtocolCodec.text(value).also {
            if (!digestPattern.matches(it)) throw CharacterException("invalid_manifest")
        }
        private fun number(value: JsonElement?): Double {
            val primitive = value as? JsonPrimitive ?: throw CharacterException("invalid_manifest")
            if (primitive.isString) throw CharacterException("invalid_manifest")
            return primitive.doubleOrNull?.takeIf { it.isFinite() } ?: throw CharacterException("invalid_manifest")
        }
        private fun obj(value: JsonElement?) = value as? JsonObject ?: throw CharacterException("invalid_manifest")
        private fun array(value: JsonElement?): JsonArray = (value as? JsonArray)?.takeIf { it.size <= 256 }
            ?: throw CharacterException("invalid_manifest")
        private fun id(value: JsonElement?): String = ProtocolCodec.text(value, 128, nonblank = true).also {
            if (it in setOf("__proto__", "constructor", "prototype") || it.any { c -> c.code < 32 }) throw CharacterException("invalid_manifest")
        }
        private fun ids(value: JsonElement?): List<String> = array(value).map(::id).also {
            if (it.distinct().size != it.size) throw CharacterException("invalid_manifest")
        }.sorted()

        fun parse(bytes: ByteArray): CharacterSnapshot {
            if (bytes.size > MAX_MANIFEST_BYTES) throw CharacterException("invalid_manifest")
            val raw = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { throw CharacterException("invalid_manifest") }
            return parse(raw)
        }

        fun parse(raw: String): CharacterSnapshot = try {
            val body = ProtocolCodec.readObject(raw, MAX_MANIFEST_BYTES)
            val status = ProtocolCodec.text(body["status"])
            if (status !in setOf("ready", "loading", "unsupported", "unavailable")) throw CharacterException("invalid_manifest")
            val runtime = ProtocolCodec.integer(body["runtime_version"], 1)
            if (runtime != RUNTIME_VERSION.toLong()) throw CharacterException("unsupported_runtime")
            val revision = ProtocolCodec.integer(body["state_revision"], 1)
            val settingsRevision = ProtocolCodec.integer(body["settings_revision"], 1)
            val actionSeq = ProtocolCodec.integer(body["action_seq"])
            val settings = ExtensionCodec.normalizeSettings(body["settings"])
            val expressions = ids(body["expression_ids"])
            val gestures = ids(body["gesture_ids"])
            val base = id(body["default_expression"])
            val patDefaults = body["head_pat_defaults"]?.let(::obj) ?: buildJsonObject {
                if (status == "ready") { put("active", "normal"); put("end", "normal") }
            }
            if (status == "ready") {
                if (patDefaults.keys != setOf("active", "end") || patDefaults.values.any { id(it) !in expressions }) throw CharacterException("invalid_manifest")
            } else if (patDefaults.isNotEmpty()) throw CharacterException("invalid_manifest")
            val assets = array(body["assets"]).map { value ->
                val item = obj(value)
                CharacterAsset(digest(item["id"]), digest(item["sha256"]),
                    ProtocolCodec.integer(item["size"], 1, MAX_ASSET_BYTES), ProtocolCodec.text(item["mime"]).also {
                        if (it !in mimeTypes) throw CharacterException("invalid_manifest")
                    })
            }.sortedBy { it.id }
            if (assets.map { it.id }.distinct().size != assets.size || assets.sumOf { it.size } > MAX_MODEL_BYTES) throw CharacterException("invalid_manifest")
            val catalog = array(body["parameter_catalog"]).map { value ->
                val item = obj(value)
                CharacterParameter(id(item["id"]), number(item["min"]), number(item["max"]), number(item["default"])).also {
                    if (it.minimum > it.maximum || it.initial !in it.minimum..it.maximum) throw CharacterException("invalid_manifest")
                }
            }.sortedBy { it.id }
            if (catalog.map { it.id }.distinct().size != catalog.size) throw CharacterException("invalid_manifest")
            val bounds = catalog.associateBy { it.id }
            val parameters = obj(body["parameters"]).mapValues { (key, value) ->
                val bound = bounds[key] ?: throw CharacterException("invalid_manifest")
                number(value).also { if (it !in bound.minimum..bound.maximum) throw CharacterException("invalid_manifest") }
            }
            val version: String?
            val entry: String?
            val modelId: String?
            if (status == "ready") {
                version = digest(body["model_version"])
                entry = digest(body["entry_asset_id"])
                modelId = digest(body["model_id"])
                if (modelId != entry || assets.none { it.id == entry && it.mime == "application/json" } || base !in expressions) throw CharacterException("invalid_manifest")
                val canonical = buildJsonObject {
                    put("assets", JsonArray(assets.map { it.descriptor() })); put("entry_asset_id", entry); put("runtime_version", runtime)
                }.toString().toByteArray(Charsets.UTF_8)
                if (hash(canonical) != version) throw CharacterException("invalid_manifest")
                for (key in listOf("head_pat_active_emotion_custom", "head_pat_end_emotion_custom")) {
                    val expression = settings[key]?.let { ProtocolCodec.text(it) }.orEmpty()
                    if (expression.isNotEmpty() && expression !in expressions) throw CharacterException("invalid_manifest")
                }
            } else {
                if (listOf("model_version", "entry_asset_id", "model_id").any { body[it] != JsonNull } ||
                    assets.isNotEmpty() || catalog.isNotEmpty() || expressions.isNotEmpty() || gestures.isNotEmpty() || parameters.isNotEmpty()) throw CharacterException("invalid_manifest")
                version = null; entry = null; modelId = null
            }
            val normalized = buildJsonObject {
                put("status", status); put("model_version", version?.let(::JsonPrimitive) ?: JsonNull)
                put("model_id", modelId?.let(::JsonPrimitive) ?: JsonNull); put("entry_asset_id", entry?.let(::JsonPrimitive) ?: JsonNull)
                put("runtime_version", runtime); put("state_revision", revision); put("settings_revision", settingsRevision); put("action_seq", actionSeq)
                put("settings", settings); put("parameters", buildJsonObject { parameters.forEach { (key, value) -> put(key, value) } })
                put("head_pat_defaults", patDefaults)
                put("parameter_catalog", buildJsonArray { catalog.forEach { item -> add(buildJsonObject {
                    put("id", item.id); put("min", item.minimum); put("max", item.maximum); put("default", item.initial)
                }) } })
                put("expression_ids", JsonArray(expressions.map(::JsonPrimitive))); put("gesture_ids", JsonArray(gestures.map(::JsonPrimitive)))
                put("default_expression", base); put("assets", JsonArray(assets.map { it.descriptor() }))
            }
            if (normalized.toString().toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) throw CharacterException("invalid_manifest")
            CharacterSnapshot(normalized, status, version, entry, revision, settingsRevision, actionSeq,
                Collections.unmodifiableList(assets), Collections.unmodifiableList(catalog), Collections.unmodifiableMap(parameters))
        } catch (error: CharacterException) { throw error }
        catch (_: IllegalArgumentException) { throw CharacterException("invalid_manifest") }
    }
}
