package dev.ene.companion.connection

enum class ConnectionStage { IDLE, SAVED_ADDRESSES, DISCOVERY, SERVER_INFO, SECURE_SESSION, INITIAL_SYNC, CONNECTED, RETRY_WAIT }
data class ConnectionFailure(val stage: ConnectionStage, val code: String)
enum class ExitRecordStatus { LOADING, AVAILABLE, UNSUPPORTED, EMPTY, UNAVAILABLE }
data class PreviousExit(val status: ExitRecordStatus = ExitRecordStatus.LOADING, val reason: Int? = null, val timestampMillis: Long? = null)
data class ConnectionDiagnostics(
    val stage: ConnectionStage = ConnectionStage.IDLE,
    val lastFailure: ConnectionFailure? = null,
    val discoveryNotice: String? = null,
    val storageNotice: String? = null,
    val previousExit: PreviousExit = PreviousExit(),
) {
    fun failed(stage: ConnectionStage, code: String) = copy(lastFailure = ConnectionFailure(stage, diagnosticCode(code)))
}
internal data class ExitSample(val process: String, val reason: Int, val timestampMillis: Long)
internal fun selectPreviousExit(records: List<ExitSample>, mainProcess: String, startedMillis: Long): PreviousExit {
    val latest = records.filter { it.process == mainProcess && it.timestampMillis in 1 until startedMillis }.maxByOrNull { it.timestampMillis }
        ?: return PreviousExit(ExitRecordStatus.EMPTY)
    return PreviousExit(ExitRecordStatus.AVAILABLE, latest.reason, latest.timestampMillis)
}

private val diagnosticCodes = RETRYABLE_CONNECTION_CODES + TLS_FAILURE_CODES + setOf(
    "connection_failed", "authorization_revoked", "authorization_unconfirmed", "registration_changed", "registration_lost",
    "invalid_connection_settings", "tls_repair_required", "unsupported_version", "invalid_server_info", "server_mismatch", "unexpected_server",
    "pairing_expired", "pairing_denied", "pairing_rejected", "registration_save_failed", "registration_clear_failed", "snapshot_too_large",
    "connection_settings_save_failed", "character_cache_cleanup_failed", "text_too_large", "conversation_changed",
    "discovery_busy", "discovery_cooldown", "discovery_no_wifi", "discovery_unavailable", "discovery_network_unavailable",
    "discovery_network_changed", "discovery_start_failed", "discovery_stop_failed", "discovery_resolver_busy", "discovery_no_candidates",
)
internal fun diagnosticCode(code: String): String = code.takeIf { it in diagnosticCodes } ?: "connection_failed"
