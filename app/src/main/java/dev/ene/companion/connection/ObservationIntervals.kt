package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ObservationIntervals(val sampleSeconds: Int = 30, val requestSeconds: Int = 300) {
    companion object { val SECONDS_RANGE = 1..86_400 }
}

data class ObservationIntervalsView(
    val supported: Boolean = false,
    val intervals: ObservationIntervals = ObservationIntervals(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: Boolean = false,
)

/** 확정된 간격은 PC에서 받는다. 늦게 도착한 응답이 더 최근 설정을 덮지 않도록 판별한다. */
internal class SharedObservationIntervals(private val nowMillis: () -> Long) {
    private val mutable = MutableStateFlow(ObservationIntervalsView())
    val state = mutable.asStateFlow()
    private var context: ExtensionContext? = null
    private var sender: ((WireMessage) -> Boolean)? = null
    private var queryId: String? = null
    private var deadline = 0L
    private var revision = 0L

    fun disconnect() {
        context = null
        sender = null
        queryId = null
        revision = 0
        mutable.value = ObservationIntervalsView()
    }

    fun connect(current: ExtensionContext, send: (WireMessage) -> Boolean) {
        if (context == current) return
        disconnect()
        context = current
        sender = send
        mutable.value = ObservationIntervalsView(supported = true)
        refresh()
    }

    fun baseChanged(epoch: String, conversation: String) {
        val current = context ?: return
        if (epoch != current.serverEpoch || conversation != current.conversationId) disconnect()
    }

    fun refresh() = request(null)

    fun save(sample: Int, request: Int) {
        if (sample !in ObservationIntervals.SECONDS_RANGE || request !in ObservationIntervals.SECONDS_RANGE ||
            !mutable.value.loaded) return
        request(ObservationIntervals(sample, request))
    }

    private fun request(intervals: ObservationIntervals?) {
        val c = context ?: return
        if (queryId != null) return
        val id = UUID.randomUUID().toString()
        queryId = id
        deadline = nowMillis() + 5000
        mutable.value = mutable.value.copy(saving = intervals != null, error = false)
        val message = if (intervals == null) {
            ObservationIntervalsGet(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, id)
        } else {
            ObservationIntervalsSet(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, id,
                intervals.sampleSeconds, intervals.requestSeconds)
        }
        if (sender?.invoke(message) != true) fail()
    }

    fun tick() {
        if (queryId != null && nowMillis() >= deadline) fail()
    }

    private fun fail() {
        queryId = null
        mutable.value = mutable.value.copy(error = true, saving = false)
    }

    fun receive(message: ObservationIntervalsState) {
        val c = context ?: return
        if (message.registration_generation != c.registrationGeneration || message.server_epoch != c.serverEpoch ||
            message.connection_generation != c.connectionGeneration || message.conversation_id != c.conversationId) return
        val reply = message.query_id != null
        if (reply && message.query_id != queryId) return
        var next = mutable.value
        if (reply) {
            queryId = null
            next = next.copy(saving = false, error = !message.saved)
        }
        if (message.revision >= revision) {
            revision = message.revision
            next = next.copy(loaded = true, intervals = ObservationIntervals(message.sample_seconds, message.request_seconds))
        }
        mutable.value = next
    }
}
