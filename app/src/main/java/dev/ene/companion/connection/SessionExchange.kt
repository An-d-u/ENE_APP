package dev.ene.companion.connection

import dev.ene.companion.protocol.*
import kotlinx.coroutines.*

/** 큰 스냅샷 해석 중에도 ping/pong은 처리한다. 수신·타이머·소비 작업은 각각 한 개뿐이다. */
internal suspend fun exchangeSession(
    socket: CompanionSocket,
    nowMillis: () -> Long,
    checkTimeout: () -> Unit,
    onExtension: ((ExtensionMessage) -> Unit)? = null,
    onBaseHeader: (WireMessage) -> Unit = {},
    onClosed: () -> Unit = {},
    consume: suspend (WireMessage) -> Unit,
): Nothing = coroutineScope {
    // 전송 callback 큐 128개/2MiB와 합쳐 대기 상한을 256개/4MiB로 유지한다.
    val inbox = SocketInbox(maxItems = 128, maxBytes = 2_097_152, onFailure = socket::cancel)
    val heartbeat = Heartbeat(nowMillis)
    var extensionTokens = 60.0
    var extensionClock = nowMillis()
    val heartbeatJob = launch {
        while (isActive) {
            delay(250)
            heartbeat.tick()?.let { if (!socket.send(it)) throw ConnectionException("connection_closed") }
        }
    }
    launch {
        try {
            while (isActive) {
                val raw = socket.receive()
                when (val frame = ProtocolCodec.decode(raw)) {
                    is Ping -> if (!socket.send(Pong(frame.nonce))) throw ConnectionException("connection_closed")
                    is Pong -> heartbeat.pong(frame.nonce)
                    is ExtensionMessage -> {
                        if (onExtension == null) inbox.offer(raw) else {
                            val now = nowMillis()
                            extensionTokens = minOf(60.0, extensionTokens +
                                (now - extensionClock).coerceAtLeast(0) * 30.0 / 1000)
                            extensionClock = maxOf(extensionClock, now)
                            if (extensionTokens < 1) throw ConnectionException("extension_rate_limited")
                            extensionTokens--
                            // 큰 snapshot 소비와 별개로 처리한다. callback은 기다리는 작업을 하지 않는다.
                            onExtension(frame)
                        }
                    }
                    else -> {
                        onBaseHeader(frame)
                        inbox.offer(raw)
                    }
                }
                yield()
            }
        } catch (error: ConnectionException) {
            if (error.code != "connection_closed") throw error
            // 수신 종료 전에 검증된 마지막 프레임은 소비자가 먼저 처리한다.
            // 상한 초과 실패가 이미 기록되었다면 finish가 그 원인을 덮지 않는다.
            heartbeatJob.cancel()
            inbox.finish()
        } finally { onClosed() }
    }
    try {
        while (true) {
            checkTimeout()
            val raw = withTimeoutOrNull(250) { inbox.receive() } ?: continue
            consume(ProtocolCodec.decode(raw))
        }
        @Suppress("UNREACHABLE_CODE") error("도달할 수 없는 세션 종료")
    } finally { inbox.fail("connection_closed") }
}
