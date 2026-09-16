package dev.ene.companion.audio

import dev.ene.companion.protocol.ExtensionContext

/** 연결과 작업을 고정한 공개 참조. 현재 전역 요청으로 재구성하지 않는다. */
data class AudioRef(
    val registrationGeneration: Long,
    val serverEpoch: String,
    val connectionGeneration: String,
    val conversationId: String,
    val messageId: String,
    val operationId: String,
    val utteranceId: String,
) {
    fun belongsTo(context: ExtensionContext): Boolean =
        registrationGeneration == context.registrationGeneration &&
            serverEpoch == context.serverEpoch &&
            connectionGeneration == context.connectionGeneration &&
            conversationId == context.conversationId
}

/** 직렬 연결 수명에서만 호출한다. Android 출력이나 네트워크를 직접 실행하지 않는다. */
class AudioSession(val ref: AudioRef, val sampleRate: Int, nowMs: Long) {
    init { require(sampleRate in 8000..48000 && nowMs >= 0) }

    var state: String = "PREPARING"
        private set
    private var deadlineMs = nowMs + 2000
    private var lastClockMs = nowMs
    private var lastReportMs = nowMs
    private var lastAckMs = nowMs
    private var lastAdvanceMs = nowMs
    private var actualFrames = 0L
    private var ackedFrames = -1L
    private val pendingAcks = linkedSetOf<Long>()
    val pendingAckCount: Int get() = pendingAcks.size

    private fun terminal(action: String): String {
        if (state == "DONE") return "ignore"
        state = "DONE"
        pendingAcks.clear()
        return action
    }

    fun tick(nowMs: Long): String {
        if (state == "DONE" || nowMs < lastClockMs) return "ignore"
        lastClockMs = nowMs
        return when {
            state == "PREPARING" && nowMs >= deadlineMs -> terminal("send_rejected")
            state == "WAITING_START" && nowMs >= deadlineMs -> terminal("send_cancel")
            state == "PLAYING" &&
                (nowMs - lastAckMs >= 5000 || nowMs - lastAdvanceMs >= 5000) ->
                terminal("send_cancel")
            else -> "ignore"
        }
    }

    fun prepared(nowMs: Long, bufferedFrames: Long, sourceEnded: Boolean, focusGranted: Boolean): String {
        if (state != "PREPARING" || nowMs < lastClockMs) return "ignore"
        val timeout = tick(nowMs)
        if (timeout != "ignore") return timeout
        if (!focusGranted || bufferedFrames !in 0..sampleRate * 4L) return terminal("send_rejected")
        if (!sourceEnded && bufferedFrames * 5 < sampleRate) return "ignore"
        state = "WAITING_START"
        deadlineMs = nowMs + 3000
        return "send_prepared"
    }

    fun start(incoming: AudioRef, context: ExtensionContext, nowMs: Long): String {
        if (incoming != ref || !ref.belongsTo(context) || state != "WAITING_START" || nowMs < lastClockMs) {
            return "ignore"
        }
        val timeout = tick(nowMs)
        if (timeout != "ignore") return timeout
        state = "PLAYING"
        lastReportMs = nowMs
        lastAckMs = nowMs
        lastAdvanceMs = nowMs
        return "play"
    }

    fun deactivate(): String = terminal(if (state == "PREPARING") "send_rejected" else "send_cancel")

    fun progress(nowMs: Long, playedFrames: Long): String {
        if (state != "PLAYING" || nowMs < lastClockMs || playedFrames < actualFrames) return "ignore"
        val timeout = tick(nowMs)
        if (timeout != "ignore") return timeout
        if (playedFrames > sampleRate * 180L) return terminal("send_cancel")
        if (playedFrames > actualFrames) lastAdvanceMs = nowMs
        actualFrames = playedFrames
        if (nowMs - lastReportMs < 100) return "ignore"
        lastReportMs = nowMs
        pendingAcks.add(playedFrames)
        if (pendingAcks.size > 50) pendingAcks.remove(pendingAcks.first())
        return "send_progress"
    }

    fun acknowledge(incoming: AudioRef, context: ExtensionContext, playedFrames: Long, nowMs: Long): String {
        if (state != "PLAYING" || incoming != ref || !ref.belongsTo(context) || nowMs < lastClockMs ||
            playedFrames <= ackedFrames || playedFrames !in pendingAcks
        ) return "ignore"
        val timeout = tick(nowMs)
        if (timeout != "ignore") return timeout
        ackedFrames = playedFrames
        lastAckMs = nowMs
        pendingAcks.removeAll { it <= playedFrames }
        return "acknowledged"
    }

    fun finishIfDrained(playedFrames: Long, receivedFrames: Long, totalFrames: Long?, httpEof: Boolean): String {
        if (state != "PLAYING") return "ignore"
        if (playedFrames !in 0..receivedFrames || receivedFrames !in 0..sampleRate * 180L ||
            (totalFrames != null && (totalFrames !in 0..sampleRate * 180L ||
                receivedFrames > totalFrames || (httpEof && receivedFrames != totalFrames)))
        ) return terminal("send_cancel")
        return if (httpEof && totalFrames != null && playedFrames == totalFrames) terminal("send_finished")
        else "ignore"
    }
}
