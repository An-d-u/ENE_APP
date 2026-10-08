package dev.ene.companion

import dev.ene.companion.presentation.*
import kotlinx.serialization.json.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MessagePresentationTest {
    @Test fun sameGoldenCasesAsDesktop() {
        val cases = Json.parseToJsonElement(javaClass.getResource("/chat_display_split_cases.json")!!.readText()).jsonArray
        for (item in cases.map { it.jsonObject }) {
            assertEquals(item.getValue("name").jsonPrimitive.content,
                item.getValue("expected").jsonArray.map { it.jsonPrimitive.content },
                splitMessageBubbles(item.getValue("text").jsonPrimitive.content, item.getValue("enabled").jsonPrimitive.boolean))
        }
    }

    @Test fun publicMaximumTextAndManyLinesAreNotTruncated() {
        val long = "x".repeat(1048576)
        assertEquals(listOf(long), splitMessageBubbles(long, true))
        val lines = "x\n".repeat(524288)
        assertEquals(524288, splitMessageBubbles(lines, true).size)
        assertEquals(listOf(lines), splitMessageBubbles(lines, false))
    }

    @Test fun localClockMatchesDesktopIncludingMidnightAndNoon() {
        val utc = ZoneId.of("UTC")
        assertEquals("AM 12:04", formatMessageTime("2030-01-01T00:04:00Z", utc))
        assertEquals("PM 12:05", formatMessageTime("2030-01-01T12:05:00Z", utc))
        assertEquals("AM 08:06", formatMessageTime("2030-01-01T23:06:00Z", ZoneId.of("Asia/Seoul")))
        assertNull(formatMessageTime("invalid", utc))
    }

    @Test fun narrowWidthAndLargeTypeMoveMetaBelow() {
        assertTrue(messageMetaBelow(250f, 1f, 148f))
        assertTrue(messageMetaBelow(360f, 2f, 148f))
        assertFalse(messageMetaBelow(400f, 1f, 100f))
    }

    @Test fun renderKeysNeverBecomeActionIdsAndLastKeySurvivesSplitting() {
        assertEquals(messageBubbleKey("synthetic-answer", 0, true), messageBubbleKey("synthetic-answer", 8, true))
        assertEquals("synthetic-answer", originalMessageId(messageBubbleKey("synthetic-answer", 3, false)))
        assertEquals("synthetic-answer", originalMessageId(messageBubbleKey("synthetic-answer", 8, true)))
        assertNull(originalMessageId("chat-notices"))
    }
}
