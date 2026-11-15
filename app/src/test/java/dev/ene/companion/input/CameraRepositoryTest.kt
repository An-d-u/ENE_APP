package dev.ene.companion.input

import dev.ene.companion.*
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ConnectionRepositoryTest.Companion.credentials
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CameraRepositoryTest {
    private class Transport : ConnectionTransport {
        val base = ChatActionRepositoryTest.ChatTransport().apply { capabilities = listOf("camera_v1") }
        val socket get() = base.socket
        val uploads = mutableListOf<ByteArray>()
        override val supportsInput = true
        override suspend fun info(endpoint: Endpoint) = base.info(endpoint)
        override suspend fun open(endpoint: Endpoint, expectedServerId: String, token: String?, pairing: Boolean) =
            base.open(endpoint, expectedServerId, token, pairing)
        override fun input(token: String, context: ExtensionContext, isCurrent: () -> Boolean) = object : InputMedia {
            override suspend fun upload(id: String, bytes: ByteArray) { check(isCurrent()); uploads.add(bytes.copyOf()) }
            override suspend fun thumbnail(message: String, image: String, revision: Long) = error("사용하지 않음")
            override fun close() {}
        }
        override fun close() = base.close()
        fun state(generation: Long, allowed: Boolean = false, model: String = "Demo Model", available: Boolean = true) {
            socket.offer(CameraState(1, epoch, id(40), conversation, generation, available, allowed, model, true))
        }
        fun reserve() {
            val request = socket.sent.filterIsInstance<InputReserve>().last()
            socket.offer(InputReserved(1, epoch, id(40), conversation, request.command_id, "camera", id(91)))
        }
    }
    private fun TestScope.repository(transport: Transport): ConnectionRepository {
        val d = StandardTestDispatcher(testScheduler)
        return ConnectionRepository(ConnectionRepositoryTest.Registrations(credentials()), ConnectionRepositoryTest.Profiles(),
            { transport }, d, d, d, nowMillis = { testScheduler.currentTime }, cameraSharingEnabled = true)
    }
    private fun TestScope.connect(repo: ConnectionRepository, transport: Transport) {
        repo.foreground(true); repo.activityResumed(true); runCurrent()
        SessionFixtures.frames(emptyList()).forEach(transport.socket::offer); runCurrent()
        assertEquals(ConnectionPhase.CONNECTED,repo.state.value.phase)
    }
    private fun TestScope.share(repo: ConnectionRepository, transport: Transport): Long {
        val initial = transport.socket.sent.filterIsInstance<CameraShare>().last()
        transport.state(initial.generation); runCurrent()
        repo.configureCameraShare(true, true, "front")
        val epoch = repo.cameraShare.value.generation
        transport.state(epoch); runCurrent()
        return epoch
    }

    @Test fun phoneQuestionUsesOneCameraUploadOnlyAndAcknowledgesTheOrdinaryDraft() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val epoch = share(repo, transport)
            var reads = 0
            repo.installCameraSource(epoch) { reads++; byteArrayOf(4, 9) }
            repo.editDraft("가상 도형을 골라 줘"); repo.sendDraft(); runCurrent()
            assertEquals(epoch, transport.socket.sent.filterIsInstance<InputReserve>().single().generation)
            transport.reserve(); runCurrent()
            val sent = transport.socket.sent.filterIsInstance<CameraQuestion>().single()
            assertEquals(epoch, sent.generation); assertEquals("image/jpeg", sent.frame.mime)
            assertArrayEquals(byteArrayOf(4, 9), transport.uploads.single()); assertEquals(1, reads)
            assertTrue(transport.socket.sent.none { it is ChatImagesSend || it is SendText || it is CameraObservation })
            transport.socket.offer(RequestStatus(dev.ene.companion.ConnectionRepositoryTest.epoch, conversation, sent.request_id, "completed", message_id = id(21))); runCurrent()
            assertEquals("", repo.state.value.draft)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun switchedLensRevokesAReservedImageAndSendsQuestionAsTextOnce() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val epoch = share(repo, transport)
            repo.installCameraSource(epoch) { byteArrayOf(7, 3) }
            repo.editDraft("가상 색을 골라 줘"); repo.sendDraft(); runCurrent()
            repo.configureCameraShare(true, true, "back")
            transport.reserve(); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<SendText>().size)
            assertEquals("camera_unavailable", repo.state.value.inputNotice)
            assertTrue(transport.uploads.isEmpty())
            assertTrue(transport.socket.sent.none { it is CameraQuestion || it is CameraObservation })
        } finally { repo.close(); runCurrent() }
    }

    @Test fun cameraObservesOnlyWithPcPermissionAndRejectsStaleGenerationsAfterStop() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val epoch = share(repo, transport)
            repo.submitCameraObservation(epoch, repo.cameraShare.value.observeEpoch, byteArrayOf(4)); runCurrent()
            assertTrue(transport.socket.sent.none { it is InputReserve })
            transport.state(epoch, allowed = true); runCurrent()
            repo.submitCameraObservation(epoch, repo.cameraShare.value.observeEpoch, byteArrayOf(9)); runCurrent()
            transport.reserve(); runCurrent()
            assertEquals(1, transport.socket.sent.filterIsInstance<CameraObservation>().size)
            repo.activityResumed(false)
            repo.submitCameraObservation(epoch, repo.cameraShare.value.observeEpoch, byteArrayOf(5)); transport.state(epoch, allowed = true); runCurrent()
            assertFalse(repo.cameraShare.value.question || repo.cameraShare.value.observe || repo.cameraShare.value.observeAllowed)
            assertEquals(1, transport.uploads.size)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun switchingModelStopsSharingAndCommandsNeverInvokeCamera() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val epoch = share(repo, transport)
            var reads = 0
            repo.installCameraSource(epoch) { reads++; byteArrayOf(12) }
            repo.editDraft("/note 가상 도형"); repo.sendDraft(); runCurrent()
            assertEquals(0, reads); assertEquals(1, transport.socket.sent.filterIsInstance<SendText>().size)
            transport.state(epoch, model = "Other Demo"); runCurrent()
            assertFalse(repo.cameraShare.value.observe || repo.cameraShare.value.question)
            assertTrue(transport.uploads.isEmpty())
        } finally { repo.close(); runCurrent() }
    }

    @Test fun oldPcKeepsPlainTextAndNeverOpensCamera() = runTest {
        val transport = Transport().apply { base.capabilities = emptyList() }; val repo = repository(transport)
        try {
            connect(repo, transport)
            repo.configureCameraShare(true, true, "front"); repo.editDraft("가상 다각형"); repo.sendDraft(); runCurrent()
            assertFalse(repo.cameraShare.value.supported)
            assertEquals(1, transport.socket.sent.filterIsInstance<SendText>().size)
            assertTrue(transport.socket.sent.none { it is CameraShare || it is InputReserve })
        } finally { repo.close(); runCurrent() }
    }

    @Test fun reconnectChecksRequestIdAndOnlyResendsTextIfThePcNeverReceivedTheQuestion() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val old = share(repo, transport)
            var reads = 0
            repo.installCameraSource(old) { reads++; byteArrayOf(8, 5) }
            repo.editDraft("가상 타일의 개수"); repo.sendDraft(); runCurrent()
            transport.reserve(); runCurrent()
            val question = transport.socket.sent.filterIsInstance<CameraQuestion>().single()
            repo.foreground(false); runCurrent(); repo.foreground(true); runCurrent()
            val newSocket = transport.socket
            assertEquals(listOf(question.request_id),newSocket.sent.filterIsInstance<SyncRequest>().single().pending_request_ids)
            newSocket.offer(RequestStatus(epoch, conversation, question.request_id, "unknown"))
            SessionFixtures.frames(emptyList()).forEach(newSocket::offer); runCurrent()
            assertEquals(question.request_id,newSocket.sent.filterIsInstance<SendText>().single().request_id)
            assertTrue(newSocket.sent.none { it is CameraQuestion || it is InputReserve })
            assertTrue(repo.cameraShare.value.generation > old)
            assertFalse(repo.cameraShare.value.question)
            assertEquals(1, reads)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun restoredOwnershipRejectsEarlierObservationFromSameLens() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val epoch = share(repo, transport)
            transport.state(epoch, allowed = true); runCurrent()
            val selected = repo.cameraShare.value
            transport.state(epoch, allowed = false); runCurrent()
            transport.state(epoch, allowed = true); runCurrent()
            repo.submitCameraObservation(selected.generation, selected.observeEpoch, byteArrayOf(2)); runCurrent()
            assertTrue(transport.socket.sent.none { it is InputReserve })
        } finally { repo.close(); runCurrent() }
    }

    @Test fun receiverReplacementWithTheSameModelNameRequiresNewConsent() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport); val old = share(repo, transport)
            transport.state(old, allowed = true); runCurrent()
            transport.state(old, available = false); transport.state(old, allowed = true); runCurrent()
            val stopped = repo.cameraShare.value
            assertTrue(stopped.generation > old)
            assertFalse(stopped.question || stopped.observe || stopped.observeAllowed)
            transport.state(stopped.generation); runCurrent()
            assertTrue(repo.cameraShare.value.available)
            assertFalse(repo.cameraShare.value.question || repo.cameraShare.value.observe)
        } finally { repo.close(); runCurrent() }
    }

    @Test fun firstNegotiationContainsOnlyADisabledSharingQuery() = runTest {
        val transport = Transport(); val repo = repository(transport)
        try {
            connect(repo, transport)
            val initial = transport.socket.sent.filterIsInstance<CameraShare>().single()
            assertFalse(initial.question || initial.observe)
            assertTrue(transport.uploads.isEmpty())
        } finally { repo.close(); runCurrent() }
    }
}
