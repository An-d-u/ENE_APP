package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.*
import dev.ene.companion.ui.ConnectionDiagnosticsDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 이번 인계에서는 계측 소스 컴파일만 확인하며 실제 단말 통과를 주장하지 않는다. */
class ConnectionDiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextCanScrollToExitExplanationAndClose() {
        var closes = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
                ConnectionDiagnosticsDialog(ConnectionDiagnostics(stage = ConnectionStage.DISCOVERY,
                    lastFailure = ConnectionFailure(ConnectionStage.SECURE_SESSION, "pc_unreachable"),
                    discoveryNotice = "discovery_no_candidates", storageNotice = "connection_settings_save_failed",
                    previousExit = PreviousExit(ExitRecordStatus.EMPTY))) { closes++ }
            }
        } }
        compose.onNodeWithText("직전 앱 종료: 이전 앱 종료 기록이 없습니다").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("닫기").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun diagnosticsUpdateDoesNotRecreateSurroundingRememberedState() {
        var state by mutableStateOf(ConnectionDiagnostics())
        var creations = 0
        compose.setContent { MaterialTheme {
            remember { creations++; Any() }
            ConnectionDiagnosticsDialog(state) {}
        } }
        compose.runOnIdle { state = state.copy(previousExit = PreviousExit(ExitRecordStatus.UNSUPPORTED)) }
        compose.onNodeWithText("직전 앱 종료: 이 Android 버전에서는 조회를 지원하지 않습니다").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, creations) }
    }
}
