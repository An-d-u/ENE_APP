package dev.ene.companion.character

/** 허용하지 않은 요청은 반드시 403 응답으로 끝낸다. 네트워크로 넘기는 null 응답은 사용하지 않는다. */
class CharacterRequestPolicy(private val modelVersion: String?, assets: Map<String, String>) {
    private val assets = assets.toMap()
    data class Resource(val path: String, val mime: String, val assetId: String? = null)

    fun resolve(url: String, method: String, mainFrame: Boolean): Resource? {
        if (method != "GET" || !url.startsWith("$ORIGIN/") || url.any { it == '%' || it == '?' || it == '#' || it == '\\' || it.code <= 32 }) return null
        val path = url.removePrefix(ORIGIN)
        if (mainFrame) return if (path == "/character/index.html") Resource("character/index.html", "text/html") else null
        val bundled = files[path]
        if (bundled != null) return Resource(path.removePrefix("/"), bundled)
        if (modelVersion == null) return null
        val prefix = "/models/$modelVersion/assets/"
        if (!path.startsWith(prefix)) return null
        val id = path.removePrefix(prefix)
        if (!Regex("[a-f0-9]{64}").matches(id)) return null
        val mime = assets[id] ?: return null
        return Resource(path, mime, id)
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val DOCUMENT = "$ORIGIN/character/index.html"
        private val scripts = setOf("entry.js", "runtime_character_state.js", "runtime_live2d_model.js",
            "runtime_motion_state.js", "runtime_gesture_engine.js", "runtime_head_pat.js", "runtime_auto_blink_tracking.js",
            "runtime_expression.js", "runtime_lipsync.js", "runtime_live2d_parameter_core.js", "runtime_character_host.js",
            "lib/pixi.min.js", "lib/live2dcubismcore.min.js", "lib/pixi-live2d-display.min.js")
        private val files = scripts.associate { "/character/$it" to "application/javascript" } +
            ("/character/style.css" to "text/css")
    }
}
