package dev.ene.companion.protocol

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** 현재 연결의 단일 스냅샷만 조립한다. 파일·화면 상태에는 접근하지 않는다. */
class SnapshotAssembler(
    private val serverEpoch: String,
    private val conversationId: String,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Pending(val begin: SnapshotBegin, val started: Long) {
        val buffer = ByteArrayOutputStream(minOf(begin.byte_count, 8192))
        var nextIndex = 0
        var progress = started
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    private var pending: Pending? = null
    val activeSnapshotId: String? get() = pending?.begin?.snapshot_id

    fun cancel() {
        pending = null
    }

    fun checkTimeout() {
        val state = pending ?: return
        val now = nowMillis()
        if (now - state.started >= 120_000 || now - state.progress >= 10_000) {
            cancel()
            throw ProtocolException("snapshot_timeout")
        }
    }

    fun consume(message: WireMessage): ConversationSnapshot? {
        try {
            checkTimeout()
            return when (message) {
                is SnapshotBegin -> {
                    if (message.server_epoch == serverEpoch && message.conversation_id == conversationId) {
                        pending = Pending(message, nowMillis())
                    }
                    null
                }
                is SnapshotPart -> {
                    val state = pending ?: return null
                    if (!matches(message.server_epoch, message.conversation_id, message.snapshot_id, state)) return null
                    if (message.index != state.nextIndex || state.nextIndex >= state.begin.part_count) throw ProtocolException("invalid_snapshot")
                    val part = ProtocolCodec.decodePart(message.data_base64)
                    val expectedSize = minOf(ProtocolCodec.PART_BYTES, state.begin.byte_count - state.buffer.size())
                    if (part.size != expectedSize) throw ProtocolException("invalid_snapshot")
                    state.buffer.write(part)
                    state.digest.update(part)
                    state.nextIndex++
                    state.progress = nowMillis()
                    null
                }
                is SnapshotEnd -> finish(message)
                else -> throw ProtocolException("invalid_snapshot")
            }
        } catch (error: ProtocolException) {
            cancel()
            throw error
        }
    }

    private fun matches(epoch: String, conversation: String, snapshot: String, state: Pending): Boolean =
        epoch == serverEpoch && conversation == conversationId && snapshot == state.begin.snapshot_id

    private fun finish(end: SnapshotEnd): ConversationSnapshot? {
        val state = pending ?: return null
        if (!matches(end.server_epoch, end.conversation_id, end.snapshot_id, state)) return null
        val begin = state.begin
        if (end.part_count != begin.part_count || end.byte_count != begin.byte_count || end.sha256 != begin.sha256 ||
            state.nextIndex != begin.part_count || state.buffer.size() != begin.byte_count
        ) throw ProtocolException("invalid_snapshot")
        val digest = state.digest.digest().joinToString("") { "%02x".format(it) }
        if (digest != begin.sha256) throw ProtocolException("invalid_snapshot")
        val (messages, processing) = try {
            ProtocolCodec.decodeSnapshot(state.buffer.toByteArray())
        } catch (_: ProtocolException) {
            throw ProtocolException("invalid_snapshot")
        }
        if (messages.size != begin.message_count) throw ProtocolException("invalid_snapshot")
        cancel()
        return ConversationSnapshot(serverEpoch, conversationId, begin.snapshot_id, begin.event_seq, begin.conversation_revision, messages, processing)
    }
}
