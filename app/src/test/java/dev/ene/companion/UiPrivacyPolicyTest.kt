package dev.ene.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 정적 안전망이다. 실제 단말의 수명·카메라·키보드 시험을 대체하지 않는다. */
class UiPrivacyPolicyTest {
    @Test fun repositoryIsOwnedByApplicationAndScreenDoesNotPersistConversation() {
        val app = File("src/main/java/dev/ene/companion/EneApplication.kt").readText()
        assertTrue(app.contains("ProcessLifecycleOwner.get().lifecycle.addObserver"))
        assertEquals(1, Regex("ConnectionRepository\\(").findAll(app).count())
        val activity = File("src/main/java/dev/ene/companion/MainActivity.kt").readText()
        assertFalse(activity.contains("ConnectionRepository("))
        assertTrue(activity.contains("FLAG_SECURE"))
        val screen = File("src/main/java/dev/ene/companion/ui/ConnectionScreen.kt").readText()
        for (forbidden in listOf("rememberSaveable", "SavedStateHandle", "Log.", "println(", "SharedPreferences")) assertFalse(screen.contains(forbidden))
    }

    @Test fun cameraRequestsPermissionOnlyOnClickAndReleasesFrames() {
        val camera = File("src/main/java/dev/ene/companion/ui/PairingCameraScreen.kt").readText()
        assertTrue(camera.contains("onRequest = { permission.launch("))
        assertTrue(camera.contains("onClick = onRequest"))
        assertTrue(camera.contains("STRATEGY_KEEP_ONLY_LATEST"))
        assertTrue(camera.contains("Barcode.FORMAT_QR_CODE"))
        assertTrue(camera.contains("proxy.close()"))
        assertFalse(camera.contains("ImageCapture"))
    }
}
