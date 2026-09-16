package dev.ene.companion.character

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.JsonPrimitive

/** 문서 안의 포인터 순서와 연결 안의 전송 순서는 분리한다. */
data class HeadPatInput(val modelVersion: String, val interactionId: String, val seq: Long, val phase: String, val intensity: Double)

/** Main 전용. 마지막 갱신 하나만 합치며 연결 수명 동안 번호를 되돌리지 않는다. */
class HeadPatSession(
    private val context: ExtensionContext,
    private val send: (HeadPat) -> Boolean,
    private val visual: (HeadPatState) -> Unit,
    private val nowMillis: () -> Long,
) {
    private data class Local(var packet: HeadPat, var inputSeq: Long, var inputAt: Long, var sentAt: Long, var pending: Boolean = false)
    private var model: String? = null
    private var enabled = false
    private var number = 0L
    private var local: Local? = null
    private var remote: HeadPatState? = null
    private var remoteAt = 0L
    private val seenIds = LinkedHashSet<String>()
    private val observed = mutableMapOf<String, HeadPatState>()

    fun configure(modelVersion: String?, allowed: Boolean) {
        if (modelVersion != model || !allowed) cancel()
        model = modelVersion
        enabled = allowed && modelVersion != null
    }

    fun input(value: HeadPatInput): Boolean {
        if (!enabled || value.modelVersion != model || !value.intensity.isFinite() || value.intensity !in 0.0..1.0 ||
            value.seq !in 0..9_007_199_254_740_990L || value.phase !in setOf("start", "update", "end", "cancel")) return false
        try { ProtocolCodec.uuid(JsonPrimitive(value.interactionId)) } catch (_: IllegalArgumentException) { return false }
        val now = nowMillis()
        if (value.phase == "start") {
            if (value.seq != 0L || local != null || value.interactionId in seenIds || number == 9_007_199_254_740_991L) return false
            seenIds += value.interactionId
            if (seenIds.size > 256) seenIds.remove(seenIds.first())
            number++
            val packet = HeadPat(context.registrationGeneration, context.serverEpoch, context.connectionGeneration,
                value.modelVersion, value.interactionId, number, 0, "start", value.intensity)
            local = Local(packet, 0, now, now)
            if (!transmit(packet)) { local = null; show(cancelled(packet, "delivery_unknown")); return false }
            return true
        }
        val active = local ?: return false
        if (active.packet.interaction_id != value.interactionId || value.seq <= active.inputSeq) return false
        active.inputSeq = value.seq; active.inputAt = now
        active.packet = active.packet.copy(intensity = value.intensity)
        if (value.phase == "update") {
            active.pending = true
            if (now - active.sentAt >= 100) flush()
            return local != null
        }
        local = null
        val packet = active.packet.copy(phase = value.phase, seq = active.packet.seq + 1)
        val delivered = transmit(packet)
        if (!delivered || value.phase == "cancel") show(cancelled(packet, if (delivered) "cancelled" else "delivery_unknown"))
        return delivered
    }

    private fun flush() {
        val active = local ?: return
        if (!active.pending) return
        active.packet = active.packet.copy(phase = "update", seq = active.packet.seq + 1)
        active.pending = false; active.sentAt = nowMillis()
        if (!transmit(active.packet)) { local = null; show(cancelled(active.packet, "delivery_unknown")) }
    }

    fun receive(value: HeadPatState, displayAllowed: Boolean = true) {
        try { ExtensionCodec.validate(value, context, setOf("character_v1", "character_controls_v1"), "from_pc") }
        catch (_: IllegalArgumentException) { return }
        val previous = observed[value.source]
        if (previous != null && (value.interaction_no < previous.interaction_no ||
                (value.interaction_no == previous.interaction_no && (value.interaction_id != previous.interaction_id || value.seq <= previous.seq)))) return
        observed[value.source] = value
        // 보이지 않는 동안에도 번호는 소비한다. 나중에 같은 시작을 몰아서 표시하지 않는다.
        if (!enabled || !displayAllowed || value.model_version != model) return
        if (value.source == "phone") {
            val active = local ?: return
            if (active.packet.interaction_id != value.interaction_id || active.packet.interaction_no != value.interaction_no) return
            // 시작/강도는 이미 로컬 예측이 표시했다. 확정 echo를 다시 입력/시작하지 않는다.
            if (value.phase in setOf("rejected", "cancelled", "ended")) { local = null; show(value) }
            return
        }
        when (value.phase) {
            "accepted" -> {
                cancelLocal("busy")
                remote = value; remoteAt = nowMillis(); show(value)
            }
            "update" -> if (remote?.interaction_id == value.interaction_id && remote?.interaction_no == value.interaction_no) {
                remote = value; remoteAt = nowMillis(); show(value)
            }
            "ended", "cancelled" -> if (remote?.interaction_id == value.interaction_id && remote?.interaction_no == value.interaction_no) {
                remote = null; show(value)
            }
        }
    }

    fun tick() {
        val now = nowMillis()
        local?.let {
            if (now - it.inputAt >= 2000) cancelLocal("input_expired")
            else if (it.pending && now - it.sentAt >= 100) flush()
        }
        remote?.let {
            if (now - remoteAt >= 2000) { remote = null; show(it.copy(seq = it.seq + 1, phase = "cancelled", reason = "lease_expired")) }
        }
    }

    fun cancel() {
        cancelLocal("cancelled")
        val previous = remote
        remote = null
        previous?.let { show(it.copy(seq = it.seq + 1, phase = "cancelled", reason = "cancelled")) }
    }

    private fun cancelLocal(reason: String) {
        val active = local ?: return
        local = null
        val packet = active.packet.copy(phase = "cancel", seq = active.packet.seq + 1)
        transmit(packet)
        show(cancelled(packet, reason))
    }

    private fun transmit(value: HeadPat): Boolean = runCatching { send(value) }.getOrDefault(false)
    private fun show(value: HeadPatState) { runCatching { visual(value) } }
    private fun cancelled(value: HeadPat, reason: String) = HeadPatState(value.registration_generation,
        value.server_epoch, value.connection_generation, value.model_version, value.interaction_id,
        value.interaction_no, value.seq, "cancelled", value.intensity, "phone", reason)
}
