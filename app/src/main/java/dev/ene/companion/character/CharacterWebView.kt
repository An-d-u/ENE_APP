package dev.ene.companion.character

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.os.Message
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream

/** 채팅·인증·네트워크 기능을 갖지 않는 캐릭터 전용 뷰. 생성/상태 주입/종료는 Main에서만 한다. */
// Compose AndroidView에서만 생성하므로 XML 도구용 무인자 콜백 생성자를 두지 않는다.
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class CharacterWebView(
    context: Context,
    private val onEvent: (CharacterEvent) -> Unit,
    private val onFailure: (String) -> Unit,
    bridgeSupported: Boolean = supported(),
) : FrameLayout(context), Closeable, CharacterRenderer {
    internal var browser: WebView? = null
        private set
    private val bridge = CharacterBridge()
    private val resourcesLock = Any()
    private var mount: CharacterCache.CachedCharacter? = null
    private var policy = CharacterRequestPolicy(null, emptyMap())
    private val streams = mutableSetOf<InputStream>()
    @Volatile private var closed = false
    private var documentReady = false
    private var initialized = false
    private var pending: CharacterSnapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val readinessTimeout = Runnable { if (!documentReady) fail("character_render_failed") }

    init {
        mainThread()
        if (!bridgeSupported || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) onFailure("character_webview_unsupported")
        else try {
            val web = WebView(context)
            browser = web
            web.also {
                web.setBackgroundColor(Color.TRANSPARENT)
                web.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                web.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false; allowContentAccess = false
                    allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    domStorageEnabled = false; databaseEnabled = false; setSaveFormData(false)
                    setGeolocationEnabled(false); javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true); mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE; blockNetworkLoads = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                ServiceWorkerController.getInstance().apply {
                    serviceWorkerWebSettings.apply { allowContentAccess = false; allowFileAccess = false; blockNetworkLoads = true }
                    setServiceWorkerClient(object : ServiceWorkerClient() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse = denied()
                    })
                }
                web.setDownloadListener { _, _, _, _, _ -> }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView?, dialog: Boolean, gesture: Boolean, result: Message?) = false
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                    override fun onConsoleMessage(message: ConsoleMessage?) = true
                    override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                        callback?.onReceiveValue(null); return true
                    }
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse =
                        resource(request.url.toString(), request.method, request.isForMainFrame)
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != CharacterRequestPolicy.DOCUMENT) fail("character_navigation_blocked")
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!closed && !initialized && url == CharacterRequestPolicy.DOCUMENT) {
                            initialized = true
                            send(bridge.initialize())
                        }
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) {
                        if (request.isForMainFrame) fail("character_render_failed")
                    }
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: android.net.http.SslError?) { handler.cancel() }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        fail("character_renderer_gone")
                        return true
                    }
                }
                WebViewCompat.addWebMessageListener(web, "eneCharacterNative", setOf(CharacterRequestPolicy.ORIGIN)) { _, message, origin, mainFrame, _ ->
                    if (message.type == WebMessageCompat.TYPE_STRING) {
                        val event = bridge.receive(origin.toString(), mainFrame, message.data.orEmpty())
                        if (event != null) {
                            if (event.type == "document_ready") {
                                documentReady = true; handler.removeCallbacks(readinessTimeout)
                                pending?.let { post("snapshot", it.json) }
                            }
                            onEvent(event)
                        }
                    }
                }
                addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                handler.postDelayed(readinessTimeout, 10_000)
                web.loadUrl(CharacterRequestPolicy.DOCUMENT)
            }
        } catch (_: Exception) { fail("character_webview_unsupported") }
    }

    /** 호출자가 가진 핀은 유지한다. 뷰는 별도 핀을 취득해 회전/교체 때 정확히 해제한다. */
    override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) {
        mainThread()
        if (closed || browser == null) return
        require((snapshot.status == "ready") == (character != null)) { "invalid_character_mount" }
        require(character == null || character.snapshot.modelVersion == snapshot.modelVersion) { "stale_character_mount" }
        val replacement = character?.retain()
        synchronized(resourcesLock) {
            streams.toList().forEach { runCatching { it.close() } }
            mount?.close(); mount = replacement
            policy = CharacterRequestPolicy(snapshot.modelVersion, snapshot.assets.associate { it.id to it.mime })
        }
        pending = snapshot
        bridge.expectModel(snapshot.modelVersion)
        if (documentReady) post("snapshot", snapshot.json)
    }

    override fun post(type: String, value: JsonObject) {
        mainThread()
        if (closed || !documentReady) return
        try { send(bridge.command(type, value)) } catch (_: IllegalArgumentException) { fail("character_render_failed") }
    }

    override fun clear() {
        // 새 연결을 기다리는 뷰는 재사용하되 이전 모델 파일/스트림은 동기적으로 해제한다.
        show(CharacterSnapshot.parse("""{
            "status":"unavailable","model_version":null,"model_id":null,"entry_asset_id":null,
            "runtime_version":1,"state_revision":1,"settings_revision":1,"action_seq":0,
            "settings":{},"parameters":{},"head_pat_defaults":{},"parameter_catalog":[],
            "expression_ids":[],"gesture_ids":[],"default_expression":"normal","assets":[]
        }"""), null)
    }

    private fun send(raw: String) {
        val web = browser ?: return
        if (!closed && WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            WebViewCompat.postWebMessage(web, WebMessageCompat(raw), Uri.parse(CharacterRequestPolicy.ORIGIN))
        }
    }

    private fun resource(url: String, method: String, mainFrame: Boolean): WebResourceResponse = synchronized(resourcesLock) {
        if (closed) return@synchronized denied()
        val item = policy.resolve(url, method, mainFrame) ?: return@synchronized denied()
        try {
            val source = if (item.assetId == null) context.assets.open(item.path) else mount?.open(item.assetId) ?: return@synchronized denied()
            val tracked = object : FilterInputStream(source) {
                private var done = false
                override fun close() = synchronized(resourcesLock) {
                    if (!done) { done = true; try { super.close() } finally { streams.remove(this) } }
                }
            }
            streams.add(tracked)
            WebResourceResponse(item.mime, if (item.mime in setOf("text/html", "text/css", "application/javascript", "application/json")) "UTF-8" else null,
                200, "OK", headers, tracked)
        } catch (_: Exception) { denied() }
    }

    private fun fail(code: String) {
        if (closed) return
        close()
        onFailure(code)
    }

    override fun close() {
        mainThread()
        if (closed) return
        closed = true; pending = null; bridge.close()
        handler.removeCallbacks(readinessTimeout)
        synchronized(resourcesLock) {
            streams.toList().forEach { runCatching { it.close() } }
            mount?.close(); mount = null
            policy = CharacterRequestPolicy(null, emptyMap())
        }
        browser?.let { web ->
            removeView(web)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(web, "eneCharacterNative") }
            }
            runCatching { web.stopLoading() }
            web.destroy()
        }
        browser = null
    }

    companion object {
        private val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; worker-src 'none'; frame-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
        private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "character_requires_main" } }
        fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) }.getOrDefault(false)
        private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", headers, ByteArrayInputStream(ByteArray(0)))
    }
}
