package dev.ene.companion.presentation

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Kotlin 기본 공백 집합과 다른 ECMAScript WhiteSpace/LineTerminator를 명시한다.
private const val JS_SPACE = "\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
private val sentences = Regex("""[^.!?。！？]+(?:[.!?。！？]+["')\]]*[$JS_SPACE]*|$)""")
private fun Char.isJsSpace(): Boolean = this in '\u0009'..'\u000D' || this == ' ' || this == '\u00A0' ||
    this == '\u1680' || this in '\u2000'..'\u200A' || this == '\u2028' || this == '\u2029' ||
    this == '\u202F' || this == '\u205F' || this == '\u3000' || this == '\uFEFF'
private fun String.jsTrim(): String = trim { it.isJsSpace() }

/** 표시만 분할한다. 원본 본문은 편집·통신·저장 경로에서 그대로 사용한다. */
fun splitMessageBubbles(text: String, enabled: Boolean): List<String> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (!enabled) return if (normalized.isEmpty()) emptyList() else listOf(normalized)
    return buildList {
        for (rawLine in normalized.lineSequence()) {
            val line = rawLine.jsTrim()
            if (line.isEmpty()) continue
            if (line.length < 72) { add(line); continue }
            val matches = sentences.findAll(line).map { it.value.jsTrim() }.filter { it.isNotEmpty() }.toList()
            if (matches.size <= 1) add(line) else addAll(matches)
        }
    }
}

fun formatMessageTime(displayedAt: String, zone: ZoneId = ZoneId.systemDefault()): String? = try {
    val time = Instant.parse(displayedAt).atZone(zone)
    String.format(Locale.ROOT, "%s %02d:%02d", if (time.hour < 12) "AM" else "PM",
        if (time.hour % 12 == 0) 12 else time.hour % 12, time.minute)
} catch (_: DateTimeException) { null }

fun messageMetaBelow(availableWidth: Float, fontScale: Float, metaWidth: Float): Boolean =
    availableWidth < 120f * fontScale + metaWidth + 8f

/** 화면의 조각 key이며 통신이나 대화 원본의 식별자로 사용하지 않는다. */
fun messageBubbleKey(messageId: String, index: Int, last: Boolean): String =
    if (last) "chat-end:$messageId" else "chat-part:$messageId:$index"

fun originalMessageId(key: String): String? = when {
    key.startsWith("chat-end:") -> key.removePrefix("chat-end:")
    key.startsWith("chat-part:") -> key.removePrefix("chat-part:").substringBeforeLast(':')
    else -> null
}

/** 공백 제거 전 본문의 위치를 보관해 분할 전환 때 읽던 내용을 다시 찾는다. */
fun messageBubbleOffsets(text: String, chunks: List<String>): IntArray {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    var next = 0
    return IntArray(chunks.size) { index ->
        val start = normalized.indexOf(chunks[index], next).coerceAtLeast(next)
        next = start + chunks[index].length
        start
    }
}

fun messageBubbleAtOffset(starts: IntArray, position: Int): Int =
    starts.binarySearch(position).let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
