package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ene.companion.connection.ConnectionPhase
import dev.ene.companion.connection.ConnectionViewState
import dev.ene.companion.ui.CameraPermissionNotice
import dev.ene.companion.ui.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 권한 설명 UI만 검증한다. OS 권한 대화상자와 실카메라는 LAN 수용 시험에서 확인한다. */
class PairingPermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deniedPermissionDoesNotRequestAgainWithoutTap() {
        var requests = 0
        compose.setContent { MaterialTheme { CameraPermissionNotice(denied = true, onRequest = { requests++ }, onSettings = {}) } }
        compose.waitForIdle()
        assertEquals(0, requests)
        compose.onNodeWithText("카메라 권한 허용").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("앱 설정 열기").assertIsDisplayed()
    }

    @Test fun syncingAndTlsFailureAreExplainedInText() {
        compose.setContent { MaterialTheme { ConnectionStatus(ConnectionViewState(phase = ConnectionPhase.SYNCING, errorCode = "tls_identity_invalid")) } }
        compose.onNodeWithText("현재 전체 대화를 가져오고 있습니다 · 전송 대기").assertIsDisplayed()
        compose.onNodeWithText("등록한 PC의 인증서와 일치하지 않아 연결을 차단했습니다. 주소 또는 PC의 새 QR을 확인해 주세요.").assertIsDisplayed()
    }
}
