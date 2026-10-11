package dev.ene.companion.input

import org.junit.Assert.*
import org.junit.Test

class SttDraftTest {
    @Test fun unchangedDraftAppendsButEditedAndUndoneDraftRequiresReview() {
        val captured = SttDraft("scope", 3)
        assertEquals("예시\n가상의 결과", captured.append("scope", 3, "예시", "가상의 결과"))
        assertNull(captured.append("scope", 5, "예시", "가상의 결과"))
        assertNull(captured.append("another", 3, "예시", "가상의 결과"))
    }
    @Test fun combinedUtf8LimitIsCheckedBeforeAppending() {
        assertNull(SttDraft("scope", 0).append("scope", 0, "a".repeat(16384), "b"))
        assertEquals("가상", SttDraft("scope", 0).append("scope", 0, "", "가상"))
    }
}
