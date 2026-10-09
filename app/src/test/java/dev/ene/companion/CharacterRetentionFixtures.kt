package dev.ene.companion

import dev.ene.companion.character.*
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.InputStream

/** 그래픽 장치만 대체하며 실제 캐시 핀·스트림 수명은 그대로 사용한다. */
internal class RetainedRenderer : CharacterRenderer, Closeable {
    var generation = 0L
    var clears = 0
    var visible = false
    var mounted: CharacterCache.CachedCharacter? = null
    var stream: InputStream? = null
    val snapshots = mutableListOf<CharacterSnapshot>()
    val commands = mutableListOf<Pair<String, JsonObject>>()
    override fun bindPresentation(generation: Long) { this.generation = generation }
    override fun present(placement: CharacterPlacement, visible: Boolean) { this.visible = visible }
    override fun show(snapshot: CharacterSnapshot, character: CharacterCache.CachedCharacter?) {
        if (mounted?.snapshot?.modelVersion != snapshot.modelVersion || mounted?.snapshot?.assets != snapshot.assets) {
            stream?.close(); stream = null; mounted?.close(); mounted = character?.retain()
        }
        snapshots += snapshot
    }
    override fun post(type: String, value: JsonObject) { commands += type to value }
    override fun clear() { clears++; stream?.close(); stream = null; mounted?.close(); mounted = null; visible = false }
    override fun close() { clear() }
}
