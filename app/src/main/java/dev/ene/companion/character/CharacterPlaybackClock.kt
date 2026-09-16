package dev.ene.companion.character

import dev.ene.companion.protocol.CharacterPlayback
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CharacterMouth(val output: String = "none", val utteranceId: String? = null,
                          val playedMs: Long = 0, val mouthOpen: Double = 0.0, val active: Boolean = false) {
    fun json() = buildJsonObject {
        put("output", output); put("played_ms", playedMs); put("mouth_open", mouthOpen); put("active", active)
    }
}

/** Main 전용. 실제 위치는 보간하지 않으며 750ms 갱신이 없으면 입만 닫는다. */
class CharacterPlaybackClock {
    private data class Sample(val value: CharacterMouth, val time: Long)
    private var phone: Sample? = null
    private var pc: Sample? = null
    private val retired = linkedSetOf<String>()

    private fun valid(value: CharacterPlayback) = value.played_ms in 0..180_000 &&
        value.mouth_open.isFinite() && value.mouth_open in 0.0..1.0

    fun local(value: CharacterPlayback, now: Long) {
        if (!valid(value) || value.output != "phone" || value.utterance_id in retired) return
        val previous = phone
        if (previous?.value?.utteranceId == value.utterance_id && value.played_ms < previous.value.playedMs) return
        if (!value.active) {
            retire(value.utterance_id)
            if (phone?.value?.utteranceId == value.utterance_id) phone = null
            if (pc?.value?.utteranceId == value.utterance_id) pc = null
        } else {
            pc?.value?.utteranceId?.takeIf { it != value.utterance_id }?.let(::retire)
            previous?.value?.utteranceId?.takeIf { it != value.utterance_id }?.let(::retire)
            pc = null
            phone = sample(value, now)
        }
    }

    fun remote(value: CharacterPlayback, now: Long) {
        if (!valid(value) || value.utterance_id in retired || phone != null) return
        val previous = pc
        if (previous?.value?.utteranceId == value.utterance_id && value.played_ms < previous.value.playedMs) return
        if (!value.active || value.output == "none") {
            if (previous?.value?.utteranceId == value.utterance_id) { retire(value.utterance_id); pc = null }
        } else if (value.output == "pc") {
            previous?.value?.utteranceId?.takeIf { it != value.utterance_id }?.let(::retire)
            pc = sample(value, now)
        } else if (value.output == "phone") {
            previous?.value?.utteranceId?.takeIf { it != value.utterance_id }?.let(::retire)
            pc = null
        }
    }

    private fun sample(value: CharacterPlayback, now: Long) = Sample(
        CharacterMouth(value.output, value.utterance_id, value.played_ms, if (value.active) value.mouth_open else 0.0, value.active), now)

    private fun retire(id: String) {
        retired.add(id)
        if (retired.size > 256) retired.remove(retired.first())
    }

    fun current(now: Long): CharacterMouth {
        val sample = phone ?: pc ?: return CharacterMouth()
        return if (now < sample.time || now - sample.time >= 750) sample.value.copy(mouthOpen = 0.0, active = false) else sample.value
    }

    fun reset() { phone = null; pc = null; retired.clear() }
}
