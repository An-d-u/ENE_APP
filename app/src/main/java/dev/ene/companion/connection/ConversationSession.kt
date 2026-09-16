package dev.ene.companion.connection

import dev.ene.companion.protocol.*

/** 한 소비자가 순서대로 호출한다. 큰 본문 해석은 Repository의 작업 dispatcher에서 수행한다. */
internal class ConversationSession(ready: Ready, private val nowMillis: () -> Long) {
    var serverEpoch = ready.server_epoch
        private set
    var conversationId = ready.conversation_id
        private set
    var snapshot: ConversationSnapshot? = null
        private set
    var syncing = true
        private set
    private var assembler = SnapshotAssembler(serverEpoch, conversationId, nowMillis)
    private var waitingSince = nowMillis()

    fun checkTimeout() {
        if (!syncing) return
        assembler.checkTimeout()
        if (assembler.activeSnapshotId == null && nowMillis() - waitingSince >= 10_000) throw ConnectionException("snapshot_timeout")
    }

    private fun invalidate(epoch: String = serverEpoch, conversation: String = conversationId): Boolean {
        val request = !syncing || epoch != serverEpoch || conversation != conversationId
        assembler.cancel()
        serverEpoch = epoch
        conversationId = conversation
        assembler = SnapshotAssembler(epoch, conversation, nowMillis)
        syncing = true
        waitingSince = nowMillis()
        return request
    }

    /** true이면 새 sync 요청 하나가 필요하다. 부분 수신은 snapshot을 바꾸지 않는다. */
    fun consume(frame: WireMessage): Boolean {
        checkTimeout()
        when (frame) {
            is ResyncRequired -> return invalidate(frame.server_epoch, frame.conversation_id)
            is SnapshotBegin, is SnapshotPart, is SnapshotEnd -> {
                if (!syncing) return false
                val completed = assembler.consume(frame) ?: return false
                val previous = snapshot
                if (previous != null && previous.serverEpoch == completed.serverEpoch && previous.conversationId == completed.conversationId &&
                    (completed.eventSeq < previous.eventSeq || completed.conversationRevision < previous.conversationRevision)) throw ConnectionException("invalid_snapshot")
                snapshot = completed
                syncing = false
            }
            is ChatEvent -> {
                if (syncing || frame.server_epoch != serverEpoch || frame.conversation_id != conversationId) return false
                val current = snapshot ?: return false
                if (frame.event_seq <= current.eventSeq) return false
                val revision = current.conversationRevision + if (frame.op == "append") 1 else 0
                if (frame.event_seq != current.eventSeq + 1 || frame.conversation_revision != revision) return invalidate()
                snapshot = when (frame.op) {
                    "append" -> {
                        val message = ProtocolCodec.decodePublicMessage(frame.payload)
                        if (current.messages.any { it.id == message.id } || current.messages.size >= 50_000) return invalidate()
                        current.copy(eventSeq = frame.event_seq, conversationRevision = revision, messages = current.messages + message)
                    }
                    "processing" -> current.copy(eventSeq = frame.event_seq, processing = ProtocolCodec.decodeProcessing(frame.payload))
                    else -> throw ConnectionException("invalid_message")
                }
            }
            is RequestStatus -> Unit
            else -> throw ConnectionException("invalid_message")
        }
        return false
    }
}
