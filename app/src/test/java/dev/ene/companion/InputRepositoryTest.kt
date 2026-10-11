package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.input.*
import dev.ene.companion.protocol.*
import dev.ene.companion.ConnectionRepositoryTest.*
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ChatActionStateTest.Companion.id
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InputRepositoryTest {
    private class Transport : ConnectionTransport {
        val base = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("chat_images_v1", "stt_input_v1") }
        val socket get() = base.socket
        var uploaded = 0
        override val supportsInput = true
        override suspend fun info(endpoint: Endpoint) = base.info(endpoint)
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean) = base.open(endpoint, expectedServerId, token, pairing)
        override fun input(token: String, context: ExtensionContext, isCurrent: () -> Boolean) = object : InputMedia {
            override suspend fun upload(id: String, bytes: ByteArray) { check(isCurrent()); uploaded++ }
            override suspend fun thumbnail(message: String, image: String, revision: Long) = byteArrayOf()
            override fun close() { }
        }
        override fun close() = base.close()
        fun allowPhotos() {
            val query = socket.sent.filterIsInstance<ChatImagesLimitsRequest>().last()
            socket.offer(ChatImagesLimits(1, epoch, id(40), conversation, query.query_id, true, "ready", 4))
        }
        fun reserve() {
            val query = socket.sent.filterIsInstance<InputReserve>().last()
            socket.offer(InputReserved(1, epoch, id(40), conversation, query.command_id, query.purpose, id(91)))
        }
    }
    private class Photos : PhotoStorage {
        val value = LocalPhoto(id(90), "image/png", "a".repeat(64), 8, 1, 1, byteArrayOf())
        var removed = 0
        override suspend fun initialize() { }
        override suspend fun prepare(source: String) = value
        override suspend fun read(photo: LocalPhoto) = ByteArray(8)
        override suspend fun delete(photos: List<LocalPhoto>) { removed += photos.size }
    }
    private fun TestScope.repository(transport: Transport, photos: Photos = Photos()): ConnectionRepository {
        val d = StandardTestDispatcher(testScheduler)
        return ConnectionRepository(Registrations(credentials()), Profiles(), { transport }, d, d, d,
            nowMillis = { testScheduler.currentTime }, photoStorage = photos,
            recordingPlatform = object : RecordingPlatform {
                override suspend fun record(maximumBytes: Int) = ByteArray(maximumBytes)
                override fun finish() { }
                override fun cancel() { }
            })
    }
    private fun TestScope.connect(repo: ConnectionRepository, transport: Transport) {
        repo.foreground(true); repo.activityResumed(true); runCurrent()
        SessionFixtures.frames(emptyList()).forEach(transport.socket::offer); runCurrent()
        transport.allowPhotos(); runCurrent()
        assertEquals(ConnectionPhase.CONNECTED, repo.state.value.phase)
    }
    @Test fun photoOnlySendUploadsOnlyAfterSendAndClearsOnlyOnAcceptance() = runTest {
        val transport = Transport(); val photos = Photos(); val repo = repository(transport, photos)
        try {
            connect(repo, transport)
            repo.selectPhotos(listOf("content://synthetic/photo")); runCurrent()
            assertEquals(0, transport.uploaded)
            assertTrue(repo.state.value.canSend)
            repo.sendDraft(); runCurrent(); transport.allowPhotos(); runCurrent(); transport.reserve(); runCurrent()
            assertEquals(1, transport.uploaded)
            val send = transport.socket.sent.filterIsInstance<ChatImagesSend>().single()
            assertEquals("", send.text)
            assertEquals(1, repo.photos.value.images.size)
            transport.socket.offer(RequestStatus(epoch, conversation, send.request_id, "accepted", message_id = id(92))); runCurrent()
            assertTrue(repo.photos.value.images.isEmpty())
            assertEquals(1, photos.removed)
            assertFalse(repo.state.value.canSend)
        } finally { repo.close(); runCurrent() }
    }
    @Test fun changedDraftRequiresReviewAndDisconnectInvalidatesReview() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport)
            repo.editDraft("가상 초안"); runCurrent(); repo.startRecording(); runCurrent()
            val begin = transport.socket.sent.filterIsInstance<SttBegin>().single()
            transport.socket.offer(SttReady(1, epoch, id(40), conversation, begin.command_id, 100, 1000)); runCurrent()
            assertEquals("recorded", repo.state.value.stt.phase)
            repo.editDraft("수정된 가상 초안"); runCurrent(); repo.finishRecording(); runCurrent(); transport.reserve(); runCurrent()
            transport.socket.offer(SttResult(1, epoch, id(40), conversation, begin.command_id, "complete", "가상의 음성 결과", "ready")); runCurrent()
            assertEquals("수정된 가상 초안", repo.state.value.draft)
            assertNotNull(repo.state.value.sttReview)
            repo.foreground(false); runCurrent()
            assertNull(repo.state.value.sttReview)
            repo.appendTranscription(); runCurrent()
            assertEquals("수정된 가상 초안", repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }
}
