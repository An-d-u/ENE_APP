package dev.ene.companion

import dev.ene.companion.protocol.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** 실제 대화와 무관한 합성 데이터만 사용한다. */
object SessionFixtures {
    fun message(text: String = "유리 구슬을 그렸습니다.", request: String? = null) = PublicMessage(
        UUID.randomUUID().toString(), "user", text, "2026-01-01T00:00:00Z", request)

    fun payload(message: PublicMessage): JsonObject = Json.encodeToJsonElement(message).jsonObject

    fun frames(messages: List<PublicMessage> = emptyList(), seq: Long = 0, revision: Long = 0,
        epoch: String = ConnectionRepositoryTest.epoch, conversation: String = ConnectionRepositoryTest.conversation,
        processing: ProcessingState = ProcessingState("idle")): List<WireMessage> {
        val bytes = buildJsonObject {
            put("messages", Json.encodeToJsonElement(messages)); put("processing", Json.encodeToJsonElement(processing))
        }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val parts = bytes.asList().chunked(ProtocolCodec.PART_BYTES).map { it.toByteArray() }
        val id = UUID.randomUUID().toString()
        return listOf(SnapshotBegin(epoch, conversation, id, seq, revision, parts.size, bytes.size, messages.size, hash)) +
            parts.mapIndexed { index, part -> SnapshotPart(epoch, conversation, id, index, Base64.getEncoder().encodeToString(part)) } +
            SnapshotEnd(epoch, conversation, id, parts.size, bytes.size, hash)
    }
}
