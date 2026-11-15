package dev.ene.companion.camera

import dev.ene.companion.connection.CameraShareView
import org.junit.Assert.*
import org.junit.Test

class CameraConsentTest {
    @Test fun permissionOrOpeningSettingsNeverStartsAnUnsharedCamera() {
        val state = CameraShareView(supported = true, available = true)
        assertFalse(state.shouldOpenCamera(true, true, true))
        val question = state.copy(question = true)
        assertTrue(question.shouldOpenCamera(true, true, true))
        assertFalse(question.shouldOpenCamera(false, true, true))
        assertFalse(question.shouldOpenCamera(true, false, true))
        assertFalse(question.shouldOpenCamera(true, true, false))
        assertFalse(question.copy(available = false).shouldOpenCamera(true, true, true))
    }

    @Test fun observeOnlyCameraWaitsWhenThePcOwnsAutomaticConversation() {
        val state = CameraShareView(supported = true, available = true, observe = true)
        assertFalse(state.shouldOpenCamera(true, true, true))
        assertTrue(state.copy(observeAllowed = true).shouldOpenCamera(true, true, true))
    }
}
