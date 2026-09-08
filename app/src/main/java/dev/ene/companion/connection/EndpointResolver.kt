package dev.ene.companion.connection

import kotlinx.serialization.Serializable
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.Locale

@Serializable
@ConsistentCopyVisibility
data class Endpoint private constructor(val host: String, val port: Int) {
    companion object {
        private val label = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

        fun parse(host: String, port: Int): Endpoint {
            val normalized = host.trim().lowercase(Locale.ROOT)
            if (port !in 1..65535 || normalized.isEmpty() || normalized.length > 253 || normalized.any { it in "/\\@?#" }) {
                throw ConnectionException("invalid_endpoint")
            }
            if (':' in normalized) throw ConnectionException("unsupported_address_family")
            val ip = ipv4(normalized)
            if (ip != null) return Endpoint(ip.joinToString("."), port)
            if (normalized.all { it.isDigit() || it == '.' }) throw ConnectionException("invalid_endpoint")
            val labels = normalized.removeSuffix(".").split('.')
            if (labels.any { !label.matches(it) }) throw ConnectionException("invalid_endpoint")
            return Endpoint(normalized, port)
        }

        private fun ipv4(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return null
            val numbers = parts.map { it.toInt() }
            return numbers.takeIf { values -> values.all { it in 0..255 } }
        }
    }

    internal fun isQrAddress(): Boolean {
        val octets = ipv4(host) ?: return false
        return octets != listOf(0, 0, 0, 0) && octets[0] != 127 && octets[0] !in 224..239 && octets != listOf(255, 255, 255, 255)
    }
}

data class ServerInfo(val serverId: String, val versions: List<Int>) {
    companion object {
        fun parse(raw: String): ServerInfo = try {
            val value = ProtocolCodec.readObject(raw, 8192)
            val serverId = ProtocolCodec.uuid(value["server_id"])
            val versions = value["protocol_versions"] as? JsonArray ?: throw ConnectionException("invalid_server_info")
            if (versions.size !in 1..8) throw ConnectionException("invalid_server_info")
            ServerInfo(serverId, Collections.unmodifiableList(versions.map { ProtocolCodec.integer(it, 1, Int.MAX_VALUE.toLong()).toInt() }))
        } catch (_: IllegalArgumentException) {
            throw ConnectionException("invalid_server_info")
        }
    }
}

/** 식별자 조회 경로에는 토큰을 전달할 수 있는 인자가 없다. */
fun interface InfoProbe {
    suspend fun info(endpoint: Endpoint): ServerInfo
}

class EndpointResolver(private val probe: InfoProbe) {
    suspend fun resolve(serverId: String, candidates: List<Endpoint>): Endpoint {
        if (candidates.size !in 1..8) throw ConnectionException("invalid_endpoint")
        var failure = "pc_unreachable"
        for (endpoint in candidates.distinct()) {
            try {
                val info = withTimeout(3000) { probe.info(endpoint) }
                if (info.serverId != serverId) { failure = "server_mismatch"; continue }
                if (1 !in info.versions) { failure = "unsupported_version"; continue }
                return endpoint
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failure = "pc_unreachable"
            } catch (error: ConnectionException) {
                failure = error.code
            }
        }
        throw ConnectionException(failure)
    }
}
