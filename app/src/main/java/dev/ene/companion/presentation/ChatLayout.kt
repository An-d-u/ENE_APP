package dev.ene.companion.presentation

import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.*

data class ChatLayout(val heightFraction: Float = .46f) {
    init { require(heightFraction.isFinite() && heightFraction in MIN_FRACTION..MAX_FRACTION) }
    companion object {
        const val MIN_FRACTION = .25f
        const val MAX_FRACTION = .84f
    }
}

interface ChatLayoutStorage {
    suspend fun load(): ChatLayout?
    suspend fun save(value: ChatLayout)
}

object ChatLayoutCodec {
    fun encode(value: ChatLayout): ByteArray = buildJsonObject {
        put("version", 1); put("heightFraction", value.heightFraction)
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ChatLayout = try {
        require(bytes.size <= 1024)
        val body = ProtocolCodec.readObject(bytes.decodeToString(throwOnInvalidSequence = true), 1024)
        require(body.keys == setOf("version", "heightFraction"))
        require(ProtocolCodec.integer(body["version"], 1, 1) == 1L)
        val height = body["heightFraction"] as? JsonPrimitive ?: error("invalid_chat_layout")
        require(!height.isString)
        ChatLayout(height.floatOrNull ?: error("invalid_chat_layout"))
    } catch (_: Exception) { throw IllegalArgumentException("invalid_chat_layout") }
}

/** 키보드·큰 글씨에 필요한 최소 높이는 표시할 때만 보정하고 저장하지 않는다. */
fun chatPanelHeight(availableHeight: Float, fraction: Float, fontScale: Float): Float {
    if (availableHeight <= 0f) return 0f
    val minimum = (260f * fontScale.coerceIn(1f, 1.5f)).coerceAtMost(availableHeight)
    val maximum = (availableHeight * ChatLayout.MAX_FRACTION).coerceAtLeast(minimum)
    return (availableHeight * fraction).coerceIn(minimum, maximum)
}

fun chatFractionAfterDrag(visibleHeight: Float, delta: Float, availableHeight: Float): Float =
    if (availableHeight <= 0f) ChatLayout().heightFraction
    else ((visibleHeight - delta) / availableHeight).coerceIn(ChatLayout.MIN_FRACTION, ChatLayout.MAX_FRACTION)

fun chatUsesCompactControls(availableHeight: Float, fontScale: Float): Boolean =
    availableHeight < 260f * fontScale.coerceIn(1f, 1.5f)

/** 좁은 IME 전경에서는 입력을 우선한다. 키보드를 닫으면 설정 진입점이 복원된다. */
fun chatHidesToolbar(usableHeight: Float, keyboard: Boolean, fontScale: Float): Boolean =
    keyboard && chatUsesCompactControls(usableHeight - 56f * fontScale.coerceIn(1f, 1.5f), fontScale)
