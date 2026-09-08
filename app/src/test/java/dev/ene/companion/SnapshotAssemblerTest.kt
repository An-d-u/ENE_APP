package dev.ene.companion

import dev.ene.companion.protocol.ProtocolCodec
import dev.ene.companion.protocol.ProtocolException
import dev.ene.companion.protocol.SnapshotAssembler
import dev.ene.companion.protocol.WireMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** 부분 데이터는 완료 검증 전까지 완성 대화로 반환하지 않는다. */
class SnapshotAssemblerTest {
    private val epoch = "00000000-0000-4000-8000-000000000001"
    private val conversation = "00000000-0000-4000-8000-000000000002"
    private val snapshot = "00000000-0000-4000-8000-000000000003"
    private val otherSnapshot = "00000000-0000-4000-8000-000000000004"

    private fun data(count: Int = 1, text: String = "가상 별 🪐"): ByteArray {
        val messages = (0 until count).joinToString(",") { index ->
            """{"id":"00000000-0000-4000-8000-${(index + 100).toString().padStart(12, '0')}","role":"assistant","text":${JsonPrimitive(text)},"displayed_at":"2026-01-01T00:00:00Z"}"""
        }
        return """{"messages":[$messages],"processing":{"phase":"idle"}}""".toByteArray(Charsets.UTF_8)
    }

    private fun frames(bytes: ByteArray, count: Int = 1, id: String = snapshot): List<WireMessage> {
        val chunks = bytes.asList().chunked(32768).map { it.toByteArray() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun frame(type: String, extra: Map<String, JsonPrimitive>) = ProtocolCodec.decode(buildJsonObject {
            put("type", type)
            put("protocol_version", 1)
            put("server_epoch", epoch)
            put("conversation_id", conversation)
            put("snapshot_id", id)
            extra.forEach { (key, value) -> put(key, value) }
        }.toString())
        val sizes = mapOf("part_count" to JsonPrimitive(chunks.size), "byte_count" to JsonPrimitive(bytes.size), "sha256" to JsonPrimitive(hash))
        return listOf(frame("snapshot_begin", sizes + mapOf("event_seq" to JsonPrimitive(7), "conversation_revision" to JsonPrimitive(3), "message_count" to JsonPrimitive(count)))) +
            chunks.mapIndexed { index, part -> frame("snapshot_part", mapOf("index" to JsonPrimitive(index), "data_base64" to JsonPrimitive(Base64.getEncoder().encodeToString(part)))) } +
            frame("snapshot_end", sizes)
    }

    @Test
    fun completeSnapshotIsPublishedAtomically() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val frames = frames(data())
        assertNull(assembler.consume(frames[0]))
        assertNull(assembler.consume(frames[1]))
        val result = requireNotNull(assembler.consume(frames.last()))
        assertEquals("가상 별 🪐", result.messages.single().text)
        assertEquals(7L, result.eventSeq)
        assertEquals(3L, result.conversationRevision)
        assertNull(assembler.activeSnapshotId)
    }

    @Test
    fun replacedSnapshotPartsAreIgnored() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val first = frames(data())
        val second = frames(data(text = "다른 가상 별"), id = otherSnapshot)
        assembler.consume(first[0])
        assembler.consume(second[0])
        assertNull(assembler.consume(first[1]))
        assertEquals(otherSnapshot, assembler.activeSnapshotId)
        assembler.consume(second[1])
        assertEquals("다른 가상 별", assembler.consume(second.last())?.messages?.single()?.text)
    }

    @Test
    fun missingOrRepeatedPartsCancelAssembly() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val frames = frames(data())
        assembler.consume(frames[0])
        assertThrows(ProtocolException::class.java) { assembler.consume(frames.last()) }
        assertNull(assembler.activeSnapshotId)
        assembler.consume(frames[0])
        assembler.consume(frames[1])
        assertThrows(ProtocolException::class.java) { assembler.consume(frames[1]) }
        assertNull(assembler.activeSnapshotId)
    }

    @Test
    fun wrongHashOrMessageCountNeverPublishes() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val frames = frames(data(), count = 2)
        assembler.consume(frames[0])
        assembler.consume(frames[1])
        assertThrows(ProtocolException::class.java) { assembler.consume(frames.last()) }
        val valid = frames(data())
        assembler.consume(valid[0])
        assembler.consume(valid[1])
        val end = Json.parseToJsonElement(ProtocolCodec.encode(valid.last())).jsonObject
        val broken = buildJsonObject { end.forEach { (key, value) -> put(key, value) }; put("sha256", "0".repeat(64)) }
        assertThrows(ProtocolException::class.java) { assembler.consume(ProtocolCodec.decode(broken.toString())) }
    }

    @Test
    fun missingProgressTimesOutAndReleasesAssembly() {
        var now = 0L
        val assembler = SnapshotAssembler(epoch, conversation, nowMillis = { now })
        val frames = frames(data())
        assembler.consume(frames[0])
        now = 10001L
        assertEquals("snapshot_timeout", assertThrows(ProtocolException::class.java) { assembler.checkTimeout() }.code)
        assertNull(assembler.activeSnapshotId)
    }

    @Test
    fun totalTimeoutExpiresEvenWithRecentProgress() {
        var now = 0L
        val assembler = SnapshotAssembler(epoch, conversation, nowMillis = { now })
        val frames = frames(data(count = 5000), count = 5000)
        assembler.consume(frames[0])
        for (index in 1..13) {
            now = index * 9000L
            assembler.consume(frames[index])
        }
        now = 120000L
        assertEquals("snapshot_timeout", assertThrows(ProtocolException::class.java) { assembler.checkTimeout() }.code)
        assertNull(assembler.activeSnapshotId)
    }

    @Test
    fun singleLargeMessageWorksWithoutExceedingFrameSize() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val frames = frames(data(text = "x".repeat(1048576)))
        frames.dropLast(1).forEach { assertNull(assembler.consume(it)) }
        assertEquals(1048576, assembler.consume(frames.last())?.messages?.single()?.text?.length)
    }

    @Test
    fun oversizeAnnouncementAndInvalidSnapshotBodyAreRejected() {
        val valid = frames(data())[0]
        val begin = Json.parseToJsonElement(ProtocolCodec.encode(valid)).jsonObject
        val oversize = buildJsonObject { begin.forEach { (key, value) -> put(key, value) }; put("byte_count", 33554433) }
        assertThrows(ProtocolException::class.java) { ProtocolCodec.decode(oversize.toString()) }
        assertThrows(ProtocolException::class.java) { ProtocolCodec.decodeSnapshot(byteArrayOf(0xFF.toByte())) }
        assertThrows(ProtocolException::class.java) { ProtocolCodec.decodeSnapshot(data(text = "x".repeat(1048577))) }
    }

    @Test
    fun fiveThousandMessagesAreComplete() {
        val assembler = SnapshotAssembler(epoch, conversation)
        val frames = frames(data(count = 5000), count = 5000)
        frames.dropLast(1).forEach { assertNull(assembler.consume(it)) }
        assertEquals(5000, assembler.consume(frames.last())?.messages?.size)
    }
}
