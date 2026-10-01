package dev.ene.companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ConnectionRepository
import dev.ene.companion.presentation.*
import dev.ene.companion.storage.*
import dev.ene.companion.ui.ConnectionScreen
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 네트워크·개인 등록 없이 실제 연결 화면의 메뉴, 상태, 입력과 배치를 함께 검사한다. */
class ConnectionOverlayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextSettingsAndStatusKeepTheDraftWithoutConnecting() {
        var viewportHeight by mutableStateOf(500.dp)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = ConnectionRepository(object : RegistrationStorage {
            override fun load(): DeviceCredentials? = null
            override fun save(credentials: DeviceCredentials) = error("등록 쓰기가 없어야 합니다")
            override fun clear() = Unit
        }, object : ConnectionSettingsStorage {
            override fun load(): ConnectionProfile? = null
            override fun save(profile: ConnectionProfile) = error("주소 쓰기가 없어야 합니다")
            override fun clear() = Unit
        })
        var saved: ChatLayout? = null
        val controller = ChatLayoutController(object : ChatLayoutStorage {
            override suspend fun load(): ChatLayout = ChatLayout(.62f)
            override suspend fun save(value: ChatLayout) { saved = value }
        }, scope, Dispatchers.Main.immediate)
        try {
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.7f)) {
                    Box(Modifier.width(320.dp).height(viewportHeight)) { ConnectionScreen(repository, controller) }
                }
            } }
            compose.onNodeWithContentDescription("메시지 · 입력 내용은 앱 종료 시 삭제됩니다")
                .assertIsDisplayed().performTextInput("새로 만든 합성 입력")
            compose.onNodeWithText("전송").assertIsNotEnabled()
            compose.onNodeWithText("배치").performClick()
            compose.onNodeWithContentDescription("대화창 높이 설정").performScrollTo().assertIsEnabled()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.7f) }
            compose.onNodeWithText("닫기").performClick()
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("새로 만든 합성 입력", repository.state.value.draft)
                assertEquals(ChatLayout(.7f), saved)
            }
            compose.onNodeWithContentDescription("연결·캐릭터·음성 상태 자세히").performClick()
            compose.onNodeWithText("PC 연결이 필요합니다").assertExists()
            compose.onNodeWithText("QR 연결").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("닫기").performClick()
            compose.runOnIdle { viewportHeight = 180.dp }
            compose.onNodeWithContentDescription("메시지 · 입력 내용은 앱 종료 시 삭제됩니다").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithText("전송").assertHeightIsAtLeast(48.dp)
            compose.runOnIdle { assertEquals(ChatLayout(.7f), controller.state.value.layout); viewportHeight = 500.dp }
            compose.onNodeWithContentDescription("연결 및 앱 설정").performClick()
            compose.onNodeWithText("PC 주소 수정").assertIsNotEnabled()
            compose.onNodeWithText("등록 해제").assertIsNotEnabled()
        } finally {
            compose.runOnIdle { repository.close(); scope.cancel() }
        }
    }
}
