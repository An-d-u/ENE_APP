package dev.ene.companion.connection

internal val RETRYABLE_CONNECTION_CODES = setOf("pc_unreachable", "connection_closed", "heartbeat_timeout", "slow_consumer",
    "snapshot_timeout", "invalid_snapshot", "request_status_timeout", "sync_required")
internal val TLS_FAILURE_CODES = setOf("tls_identity_invalid", "tls_expired", "tls_clock_invalid")

/** 신뢰되지 않은 주소의 실패는 등록 삭제나 전체 프로토콜 실패의 근거가 아니다. */
internal fun candidateFailure(failures: List<ConnectionException>): ConnectionException {
    failures.firstOrNull { it.code == "authorization_revoked" && it.peerAuthenticated }?.let { return it }
    val authenticated = failures.filter { it.peerAuthenticated && it.code !in RETRYABLE_CONNECTION_CODES && it.code !in TLS_FAILURE_CODES }
    val priority = listOf("registration_changed", "authorization_unconfirmed", "unsupported_version", "invalid_server_info", "unexpected_server")
    authenticated.minWithOrNull(compareBy<ConnectionException> {
        priority.indexOf(it.code).takeIf { index -> index >= 0 } ?: priority.size
    }.thenBy { it.code })?.let { return it }
    return failures.filter { it.code in RETRYABLE_CONNECTION_CODES }.minByOrNull { it.code }
        ?: ConnectionException("pc_unreachable")
}

/** 새 WSS 협상에서 인증서가 바뀐 오류를 이전 /info의 신뢰로 덮어쓰지 않는다. */
internal fun authenticatedSessionFailure(code: String): ConnectionException =
    ConnectionException(code, peerAuthenticated = code !in TLS_FAILURE_CODES)
