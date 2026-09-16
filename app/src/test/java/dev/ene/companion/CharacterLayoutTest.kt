package dev.ene.companion

import dev.ene.companion.ui.characterPanelHeight
import org.junit.Assert.*
import org.junit.Test

class CharacterLayoutTest {
    @Test fun smallScreenKeyboardAndLargeTextLeaveRoomForChatInput() {
        assertEquals(0, characterPanelHeight(400, false, 1f))
        assertEquals(0, characterPanelHeight(800, true, 1f))
        assertEquals(0, characterPanelHeight(800, false, 1.5f))
        assertEquals(200, characterPanelHeight(800, false, 1f))
        assertEquals(220, characterPanelHeight(1600, false, 1f))
    }
}
