package dev.ene.companion.character

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*

data class CharacterSettingsViewState(
    val open: Boolean = false,
    val available: Boolean = false,
    val busy: Boolean = false,
    val snapshot: CharacterSnapshot? = null,
    val result: String? = null,
)

/** Main 전용. 화면을 닫아도 전송 중 명령의 결과는 추적하지만 자동 재전송하지 않는다. */
class CharacterSettingsState(
    private val context: ExtensionContext,
    private val send: (CharacterSettingsPatch) -> Boolean,
    private val showPreview: (CharacterSnapshot) -> Unit,
    private val requestSnapshot: () -> Unit,
    private val nowMillis: () -> Long,
) {
    private val controls = CharacterControls()
    private var confirmed: CharacterSnapshot? = null
    private var displayed: CharacterSnapshot? = null
    private var shown = false
    private var enabled = false
    private var deadline: Long? = null
    private val changes = mutableMapOf<String, JsonElement>()
    private val parameters = mutableMapOf<String, JsonElement>()
    private val busy get() = controls.phase in setOf("sending", "refreshing")
    val view get() = CharacterSettingsViewState(shown, enabled && confirmed?.status == "ready", busy,
        displayed ?: confirmed, controls.lastResult)

    fun available(value: Boolean) {
        enabled = value
        if (!value) close()
    }

    fun install(snapshot: CharacterSnapshot) {
        val before = confirmed
        if (before != null && (snapshot.stateRevision < before.stateRevision || snapshot.settingsRevision < before.settingsRevision)) return
        confirmed = snapshot
        val changed = before?.modelVersion != snapshot.modelVersion || before?.settingsRevision != snapshot.settingsRevision
        val hadPreview = changes.isNotEmpty() || parameters.isNotEmpty()
        if (changed) { clearPreview(); if (hadPreview) restore() }
        if (snapshot.status != "ready") { close(); return }
        if (controls.phase == "refreshing") {
            if (controls.refresh(snapshot)) { clearPreview(); if (!shown) restore() }
        } else if (!busy && (changed || controls.phase == "idle")) controls.begin(snapshot)
        if (displayed == null) displayed = snapshot
    }

    fun open() {
        if (!view.available || busy) return
        controls.begin(confirmed!!); shown = true; clearPreview()
    }

    fun close() {
        val hadPreview = changes.isNotEmpty() || parameters.isNotEmpty()
        shown = false; clearPreview()
        if (hadPreview) restore()
    }

    fun preview(key: String, value: JsonElement, parameter: Boolean = false): Boolean {
        if (!shown || !view.available || busy) return false
        val settingsPatch = if (parameter) emptyMap() else mapOf(key to value)
        val parameterPatch = if (parameter) mapOf(key to value) else emptyMap()
        try { controls.validate(JsonObject(settingsPatch), JsonObject(parameterPatch)) }
        catch (_: IllegalArgumentException) { return false }
        val snapshot = confirmed ?: return false
        val target = if (parameter) parameters else changes
        val original = if (parameter) snapshot.json.getValue("parameters").jsonObject[key] ?: JsonNull
            else snapshot.json.getValue("settings").jsonObject[key]
        if (value == original) target.remove(key) else target[key] = value
        val values = snapshot.json.getValue("parameters").jsonObject.toMutableMap()
        parameters.forEach { (id, item) -> if (item == JsonNull) values.remove(id) else values[id] = item }
        val local = CharacterSnapshot.parse(JsonObject(snapshot.json + mapOf(
            "settings" to JsonObject(snapshot.json.getValue("settings").jsonObject + changes),
            "parameters" to JsonObject(values),
        )).toString())
        displayed = local; showPreview(local)
        return true
    }

    fun submit() {
        if (!shown || !view.available || busy || (changes.isEmpty() && parameters.isEmpty())) return
        val patch = controls.submit(context, JsonObject(changes.toMap()), JsonObject(parameters.toMap()))
        deadline = nowMillis() + 10_000
        if (!runCatching { send(patch) }.getOrDefault(false)) unknown()
    }

    fun receive(result: CharacterSettingsResult) {
        if (!controls.receive(result)) return
        deadline = null; clearPreview(); restore(); requestSnapshot()
    }

    fun tick() { if (deadline?.let { nowMillis() >= it } == true) unknown() }

    private fun unknown() {
        deadline = null; controls.deliveryUnknown(); clearPreview(); restore(); requestSnapshot()
    }

    private fun clearPreview() { changes.clear(); parameters.clear(); displayed = confirmed }
    private fun restore() { confirmed?.takeIf { it.status == "ready" }?.let(showPreview) }
}
