package dev.ene.companion.character

import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID

data class CharacterEvent(val type: String, val modelVersion: String? = null, val code: String? = null)

/** WebView의 출처/프레임 확인 뒤에도 문서 세대와 작은 허용 메시지만 수락한다. Main 전용. */
class CharacterBridge(val generation: String = UUID.randomUUID().toString()) : Closeable {
    private var closed = false
    private var documentReady = false
    private var modelVersion: String? = null
    init { ProtocolCodec.uuid(JsonPrimitive(generation)) }

    fun expectModel(version: String?) {
        require(version == null || Regex("[a-f0-9]{64}").matches(version)) { "invalid_model" }
        modelVersion = version
    }

    fun initialize(): String = buildJsonObject { put("type", "initialize"); put("generation", generation) }.toString()

    fun command(type: String, value: JsonObject): String {
        check(!closed && documentReady) { "character_not_ready" }
        require(type in setOf("snapshot", "action", "playback")) { "unknown_command" }
        return buildJsonObject { put("type", type); put("generation", generation); put("value", value) }.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= CharacterSnapshot.MAX_MANIFEST_BYTES + 256) { "character_command_too_large" }
        }
    }

    fun receive(origin: String, mainFrame: Boolean, raw: String): CharacterEvent? {
        if (closed || origin != CharacterRequestPolicy.ORIGIN || !mainFrame || raw.length > 2048) return null
        return try {
            val body = ProtocolCodec.readObject(raw, 2048)
            if (ProtocolCodec.uuid(body["generation"]) != generation) return null
            val type = ProtocolCodec.text(body["type"])
            if (type == "document_ready") {
                if (documentReady) return null
                documentReady = true
                return CharacterEvent(type)
            }
            if (!documentReady) return null
            when (type) {
                "ready", "unavailable" -> {
                    val version = if (body["model_version"] == JsonNull) null else CharacterSnapshot.digest(body["model_version"])
                    if (version != modelVersion || (type == "ready" && version == null)) null else CharacterEvent(type, version)
                }
                "error" -> {
                    if (ProtocolCodec.text(body["code"]) != "character_render_failed") null
                    else CharacterEvent(type, code = "character_render_failed")
                }
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    override fun close() { closed = true; documentReady = false; modelVersion = null }
}
