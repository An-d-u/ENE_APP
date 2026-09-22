package dev.ene.companion

import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ene.companion.character.CharacterWebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 단말 실행은 별도 승인 단계다. 현재는 계측 APK의 컴파일만 검증한다. */
@RunWith(AndroidJUnit4::class)
class CharacterWebViewTest {
    @Test fun bundledDocumentCompletesReplyChannelHandshakeAndReceivesSnapshot() {
        val completed = CountDownLatch(1)
        var view: CharacterWebView? = null
        var failure: String? = null
        var ready = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                view = CharacterWebView(ApplicationProvider.getApplicationContext(), { event ->
                    if (event.type == "document_ready") ready = true
                    if (event.type == "unavailable") completed.countDown()
                    if (event.type == "error") { failure = event.code; completed.countDown() }
                }, { failure = it; completed.countDown() })
                view!!.clear()
            }
            assertTrue("내부 문서의 준비/스냅샷 응답이 필요합니다", completed.await(15, TimeUnit.SECONDS))
            assertNull(failure)
            assertTrue(ready)
        } finally { instrumentation.runOnMainSync { view?.close() } }
    }

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
