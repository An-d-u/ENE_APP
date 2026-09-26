package dev.ene.companion.character

import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.*

data class CharacterPlacement(val scale: Double = 1.0, val xPercent: Double = 50.0, val yPercent: Double = 50.0) {
    init {
        require(scale.isFinite() && scale in .5..2.0)
        require(xPercent.isFinite() && xPercent in 0.0..100.0)
        require(yPercent.isFinite() && yPercent in 0.0..100.0)
    }
    fun json(): JsonObject = buildJsonObject { put("scale", scale); put("xPercent", xPercent); put("yPercent", yPercent) }
    fun presentation(visible: Boolean): JsonObject = buildJsonObject { put("placement", json()); put("visible", visible) }
}

interface CharacterPlacementStorage {
    suspend fun load(): CharacterPlacement?
    suspend fun save(value: CharacterPlacement)
}

object CharacterPlacementCodec {
    fun encode(value: CharacterPlacement): ByteArray = JsonObject(value.json() + ("version" to JsonPrimitive(1))).toString().toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): CharacterPlacement = try {
        require(bytes.size <= 8192)
        val body = ProtocolCodec.readObject(bytes.decodeToString(throwOnInvalidSequence = true), 8192)
        require(body.keys == setOf("version", "scale", "xPercent", "yPercent"))
        require(ProtocolCodec.integer(body["version"], 1, 1) == 1L)
        fromJson(JsonObject(body - "version"))
    } catch (_: Exception) { throw IllegalArgumentException("invalid_character_placement") }

    fun fromJson(body: JsonObject): CharacterPlacement {
        require(body.keys == setOf("scale", "xPercent", "yPercent"))
        fun number(key: String): Double {
            val value = body[key] as? JsonPrimitive ?: throw IllegalArgumentException("invalid_character_placement")
            require(!value.isString)
            return value.doubleOrNull ?: throw IllegalArgumentException("invalid_character_placement")
        }
        return CharacterPlacement(number("scale"), number("xPercent"), number("yPercent"))
    }
}
