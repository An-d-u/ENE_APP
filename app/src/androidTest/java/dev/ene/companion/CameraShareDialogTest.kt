package dev.ene.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ene.companion.connection.CameraShareView
import dev.ene.companion.ui.CameraShareDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraShareDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionAndModeChoicesNeedASeparateStart() {
        var requests = 0
        var configurations = 0
        compose.setContent {
            MaterialTheme {
                CameraShareDialog(CameraShareView(supported = true, available = true, model = "합성 모델", remote = true),
                    true, false, false, null, null, { requests++ }, {},
                    { _, _, _ -> configurations++ }, {}, {})
            }
        }
        compose.onNodeWithText("이미지를 받을 외부 모델 · 합성 모델").assertExists()
        compose.onNodeWithText("전면").assertIsNotEnabled()
        compose.onNodeWithText("카메라를 보고 먼저 말 걸기").performScrollTo().performClick()
        compose.onNodeWithText("카메라 권한 허용").performScrollTo().performClick()
        compose.onNodeWithText("공유 시작").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(0, configurations) }
    }
}
