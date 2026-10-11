package dev.ene.companion.input

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 연결 한 개의 업로드 예약. WSS 수신 루프와 독립적으로 기다리며 종료 시 HTTP도 회수한다. */
class InputSession(val context: ExtensionContext, private val media: InputMedia,
                   private val send: (WireMessage) -> Boolean) : AutoCloseable {
    private val uploadLock = Mutex()
    private val waiting = mutableMapOf<String, CompletableDeferred<InputReserved>>()
    private val limits = mutableMapOf<String, CompletableDeferred<ChatImagesLimits>>()
    private val reserved = mutableMapOf<String, Pair<String, String>>()
    private var closed = false
    fun receive(frame: ExtensionMessage) {
        if (closed || frame.registration_generation != context.registrationGeneration || frame.server_epoch != context.serverEpoch ||
            frame.connection_generation != context.connectionGeneration) return
        when (frame) {
            is InputReserved -> if (frame.conversation_id == context.conversationId) waiting[frame.command_id]?.complete(frame)
            is ChatImagesLimits -> if (frame.conversation_id == context.conversationId) limits[frame.query_id]?.complete(frame)
            is ExtensionError -> {
                waiting[frame.command_id]?.completeExceptionally(ConnectionException(frame.code))
                limits[frame.command_id]?.completeExceptionally(ConnectionException(frame.code))
            }
            else -> Unit
        }
    }
    suspend fun photoLimits(): ChatImagesLimits {
        check(!closed)
        val c = context
        val query = UUID.randomUUID().toString()
        val response = CompletableDeferred<ChatImagesLimits>().also { limits[query] = it }
        try {
            if (!send(ChatImagesLimitsRequest(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId, query)))
                throw ConnectionException("connection_closed")
            return withTimeout(5000) { response.await() }
        } finally { limits.remove(query) }
    }
    suspend fun upload(purpose: String, bytes: ByteArray): InputReserved = uploadLock.withLock {
        check(!closed)
        val command = UUID.randomUUID().toString()
        val response = CompletableDeferred<InputReserved>()
        waiting[command] = response
        try {
            val c = context
            if (!send(InputReserve(c.registrationGeneration, c.serverEpoch, c.connectionGeneration, c.conversationId,
                    command, purpose, bytes.size.toLong(), bytes.sha256()))) throw ConnectionException("connection_closed")
            val slot = withTimeout(5000) { response.await() }
            if (closed || slot.purpose != purpose) throw ConnectionException("stale_extension")
            reserved[slot.upload_id] = command to purpose
            media.upload(slot.upload_id, bytes)
            slot
        } finally { waiting.remove(command) }
    }
    fun consumed(ids: List<String>) { ids.forEach(reserved::remove) }
    fun cancelUploads() {
        val c = context
        reserved.toMap().forEach { (id, value) -> send(InputCancel(c.registrationGeneration, c.serverEpoch,
            c.connectionGeneration, c.conversationId, value.first, value.second, id)) }
        reserved.clear()
    }
    suspend fun thumbnail(message: String, image: String, revision: Long) = media.thumbnail(message, image, revision)
    override fun close() {
        closed = true
        waiting.values.forEach { it.cancel() }; waiting.clear()
        limits.values.forEach { it.cancel() }; limits.clear()
        reserved.clear(); media.close()
    }
}
