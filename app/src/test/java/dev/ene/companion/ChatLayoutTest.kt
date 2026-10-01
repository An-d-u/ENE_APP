package dev.ene.companion

import dev.ene.companion.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ChatLayoutTest {
    @Test fun shortViewportReservesInputAndOnlyHidesToolbarForTheKeyboard() {
        assertTrue(chatUsesCompactControls(96f, 1f))
        assertTrue(chatUsesCompactControls(300f, 1.7f))
        assertFalse(chatUsesCompactControls(400f, 1.7f))
        assertTrue(chatHidesToolbar(180f, true, 1f))
        assertFalse(chatHidesToolbar(180f, false, 1f))
        assertFalse(chatHidesToolbar(500f, true, 1f))
    }

    @Test fun onlyFinitePhoneHeightIsStoredAndRestored() {
        for (value in listOf(ChatLayout(), ChatLayout(.25f), ChatLayout(.84f), ChatLayout(.62f))) {
            assertEquals(value, ChatLayoutCodec.decode(ChatLayoutCodec.encode(value)))
        }
        assertEquals(.46f, ChatLayout().heightFraction, 0f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, .249f, .841f)) {
            assertThrows(IllegalArgumentException::class.java) { ChatLayout(value) }
        }
    }

    @Test fun corruptFutureOrForeignDataIsRejected() {
        for (raw in listOf("{}", "[]", "broken",
            """{"version":2,"heightFraction":0.5}""",
            """{"version":1,"heightFraction":"0.5"}""",
            """{"version":1,"heightFraction":0.9}""",
            """{"version":1,"heightFraction":0.5,"draft":"합성 예시"}""")) {
            assertThrows(IllegalArgumentException::class.java) { ChatLayoutCodec.decode(raw.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { ChatLayoutCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { ChatLayoutCodec.decode(ByteArray(1025)) }
    }

    @Test fun viewportClampsAreTemporaryAndKeepInputUsable() {
        val saved = ChatLayout(.46f)
        assertEquals(368f, chatPanelHeight(800f, saved.heightFraction, 1f), .01f)
        assertEquals(260f, chatPanelHeight(400f, saved.heightFraction, 1f), .01f)
        assertEquals(200f, chatPanelHeight(200f, saved.heightFraction, 1.7f), .01f)
        assertEquals(390f, chatPanelHeight(500f, saved.heightFraction, 1.7f), .01f)
        assertEquals(0f, chatPanelHeight(0f, saved.heightFraction, 1f), 0f)
        assertEquals(368f, chatPanelHeight(800f, saved.heightFraction, 1f), .01f)
        assertEquals(.46f, saved.heightFraction, 0f)
    }

    @Test fun draggingUsesVisibleHeightAndClampsAtBothEnds() {
        assertEquals(.6f, chatFractionAfterDrag(400f, -80f, 800f), .001f)
        assertEquals(.4f, chatFractionAfterDrag(400f, 80f, 800f), .001f)
        assertEquals(.84f, chatFractionAfterDrag(400f, -10000f, 800f), 0f)
        assertEquals(.25f, chatFractionAfterDrag(400f, 10000f, 800f), 0f)
        // 최소 높이로 보정된 화면에서도 첫 드래그가 저장 비율 쪽으로 튀지 않는다.
        assertEquals(.625f, chatFractionAfterDrag(260f, 10f, 400f), .001f)
    }
}
