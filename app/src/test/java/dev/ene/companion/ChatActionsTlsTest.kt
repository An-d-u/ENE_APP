package dev.ene.companion

import dev.ene.companion.connection.*
import dev.ene.companion.protocol.*
import dev.ene.companion.storage.DeviceCredentials
import dev.ene.companion.storage.ConnectionProfile
import dev.ene.companion.ConnectionRepositoryTest.Companion.epoch
import dev.ene.companion.ConnectionRepositoryTest.Companion.conversation
import dev.ene.companion.ChatActionStateTest.Companion.id
import dev.ene.companion.ChatActionStateTest.Companion.pair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.*
import okhttp3.*
import okhttp3.tls.HandshakeCertificates
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 실제 Android 전송/Repository와 합성 WSS 서버다. 실기기 PC 왕복 시험은 아니다. */
class ChatActionsTlsTest {
    @Test fun pinnedWssCarriesEditsAndFreshConfirmationWithoutMediaPlatforms() = runBlocking {
        val ca = TlsTestCertificates.ca()
        val serverId = TlsTestCertificates.SERVER_ID
        val credentials = DeviceCredentials.create(serverId, id(90), 1, ConnectionRepositoryTest.secret(), TlsTestCertificates.encoded(ca.certificate))
        val actions = CopyOnWriteArrayList<ChatAction>()
        val queries = CopyOnWriteArrayList<ChatActionsRequest>()
        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(TlsTestCertificates.leaf(ca)).build().sslSocketFactory())
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"server_id":"$serverId","protocol_versions":[1]}""").build())
            server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                var messages = pair
                var revision = 2L
                var seq = 4L
                var stateSeq = 0L
                fun send(socket: WebSocket, value: WireMessage) { check(socket.send(ProtocolCodec.encode(value))) }
                fun snapshot(socket: WebSocket): String {
                    val frames = SessionFixtures.frames(messages, seq, revision)
                    frames.forEach { send(socket, it) }
                    return (frames.last() as SnapshotEnd).snapshot_id
                }
                fun available(socket: WebSocket, query: String? = null, snapshot: String? = null) {
                    send(socket, ChatActionsState(1, epoch, id(40), conversation, revision, seq, ++stateSeq,
                        id(5), id(6), true, "ready", true, "ready", query, snapshot))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = ProtocolCodec.decode(text)) {
                        is Hello -> {
                            assertEquals(listOf("chat_actions_v1"), message.capabilities)
                            send(webSocket, Ready(serverId, epoch, conversation, 1, message.capabilities))
                            send(webSocket, ExtensionsReady(1, epoch, id(40), message.capabilities))
                        }
                        is SyncRequest -> { snapshot(webSocket); available(webSocket) }
                        is ChatAction -> {
                            actions.add(message)
                            send(webSocket, RequestStatus(epoch, conversation, message.request_id, "accepted", id(5)))
                            messages = messages.map { when {
                                it.role == "user" && message.kind == "edit" -> it.copy(text = requireNotNull(message.text))
                                it.role == "assistant" -> it.copy(text = "가상 WSS 교체 답변 ${actions.size}")
                                else -> it
                            } }
                            revision += 2; seq += 4
                            send(webSocket, RequestStatus(epoch, conversation, message.request_id, "completed", id(5)))
                        }
                        is ChatActionsRequest -> {
                            queries.add(message)
                            available(webSocket, message.query_id, if (message.refresh) snapshot(webSocket) else null)
                        }
                        is Ping -> send(webSocket, Pong(message.nonce))
                        else -> Unit
                    }
                }
            }).build())
            val profiles = ConnectionRepositoryTest.Profiles().apply {
                value = ConnectionProfile(serverId, listOf(Endpoint.parse("127.0.0.1", server.port)))
            }
            val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val repo = ConnectionRepository(ConnectionRepositoryTest.Registrations(credentials), profiles,
                dispatcher = dispatcher, ioDispatcher = Dispatchers.IO)
            try {
                repo.foreground(true).join()
                withTimeout(5000) { repo.state.first { it.chatActions.canEdit } }
                repo.editDraft("가상 새 입력 초안").join()
                repo.openMessageEditor(id(5)).join(); repo.editMessageDraft("가상 WSS 편집 요청").join(); repo.submitMessageEdit().join()
                val edited = withTimeout(5000) { repo.state.first { !it.chatActions.busy && it.messages.firstOrNull()?.text == "가상 WSS 편집 요청" } }
                assertEquals("가상 새 입력 초안", edited.draft)
                assertNull(edited.chatActions.editor)
                repo.rerollMessage(id(6)).join()
                val rerolled = withTimeout(5000) { repo.state.first { !it.chatActions.busy && it.messages.lastOrNull()?.text == "가상 WSS 교체 답변 2" } }
                assertEquals(listOf(id(5), id(6)), rerolled.messages.map { it.id })
                assertEquals(listOf("edit", "reroll"), actions.map { it.kind })
                assertEquals(2, queries.count { it.refresh })
                assertFalse(rerolled.audioOutput.reason == "ready")
                server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
                val request = requireNotNull(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("Bearer ${credentials.token}", request.headers["Authorization"])
            } finally { repo.foreground(false).join(); repo.close(); dispatcher.close() }
        }
    }
}
