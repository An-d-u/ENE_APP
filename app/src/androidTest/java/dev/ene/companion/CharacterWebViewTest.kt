package dev.ene.companion

import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ene.companion.character.CharacterWebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 단말 실행은 별도 승인 단계다. 현재는 계측 APK의 컴파일만 검증한다. */
@RunWith(AndroidJUnit4::class)
class CharacterWebViewTest {
    @Test fun unavailableSecureBridgeDoesNotCreateFallbackWebView() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var failure: String? = null
            val view = CharacterWebView(ApplicationProvider.getApplicationContext(), {}, { failure = it }, bridgeSupported = false)
            assertNull(view.browser)
            assertEquals("character_webview_unsupported", failure)
            view.close()
        }
    }

    @Test fun webSettingsDenyFilesStorageNetworkAndMixedContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = CharacterWebView(ApplicationProvider.getApplicationContext(), {}, {})
            view.browser?.settings?.let { settings ->
                assertFalse(settings.allowFileAccess)
                assertFalse(settings.allowContentAccess)
                assertFalse(settings.domStorageEnabled)
                assertTrue(settings.blockNetworkLoads)
                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
                assertTrue(settings.mediaPlaybackRequiresUserGesture)
            }
            view.close()
            view.close()
            assertEquals(0, view.childCount)
        }
    }
}
