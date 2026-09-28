package dev.ene.companion.connection

import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex

data class Ipv4Prefix(val address: Long, val length: Int)
data class LanScope(val networkHandle: Long, val prefixes: List<Ipv4Prefix>)
data class DiscoveryResult(val endpoints: List<Endpoint> = emptyList(), val network: LanScope? = null, val notice: String? = null)
interface PcDiscovery {
    suspend fun discover(excluded: Set<Endpoint>): DiscoveryResult
    fun isCurrent(network: LanScope): Boolean
}
object NoPcDiscovery : PcDiscovery {
    override suspend fun discover(excluded: Set<Endpoint>) = DiscoveryResult()
    override fun isCurrent(network: LanScope) = false
}

internal class DiscoveryFailure(val code: String) : Exception(code)
internal data class ResolvedService(val addresses: List<String>, val port: Int, val attributes: Map<String, String>)
internal interface DiscoveryService {
    val key: String
    val type: String
    suspend fun resolve(): ResolvedService?
}
internal sealed interface DiscoveryEvent {
    data class Found(val service: DiscoveryService) : DiscoveryEvent
    data class Lost(val key: String) : DiscoveryEvent
    data class Failed(val code: String) : DiscoveryEvent
    data object NetworkChanged : DiscoveryEvent
}
internal interface DiscoveryBackend {
    fun currentNetwork(): LanScope?
    fun browse(network: LanScope, emit: (DiscoveryEvent) -> Unit): Closeable
}
internal fun ipv4Number(host: String): Long? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    var result = 0L
    for (part in parts) {
        if (!Regex("0|[1-9][0-9]{0,2}").matches(part)) return null
        val value = part.toInt()
        if (value !in 0..255) return null
        result = (result shl 8) or value.toLong()
    }
    return result
}
internal fun belongsToPrefix(address: Long, prefix: Ipv4Prefix): Boolean {
    if (address !in 0..0xffff_ffffL || prefix.address !in 0..0xffff_ffffL || prefix.length !in 1..32) return false
    val mask = (0xffff_ffffL shl (32 - prefix.length)) and 0xffff_ffffL
    if ((address and mask) != (prefix.address and mask)) return false
    val broadcast = (prefix.address and mask) or (mask xor 0xffff_ffffL)
    return prefix.length >= 31 || address != broadcast
}
internal fun discoveryEndpoint(host: String, port: Int, network: LanScope): Endpoint? {
    val number = ipv4Number(host) ?: return null
    val first = (number shr 24).toInt()
    if (first == 0 || first == 127 || first in 224..255 || network.prefixes.none { belongsToPrefix(number, it) }) return null
    return try { Endpoint.parse(host, port) } catch (_: ConnectionException) { null }
}
internal class BoundedPcDiscovery(private val backend: DiscoveryBackend, private val nowMillis: () -> Long) : PcDiscovery {
    private val running = Mutex()
    private var lastStart: Long? = null
    override fun isCurrent(network: LanScope) = try { backend.currentNetwork() == network } catch (_: Exception) { false }
    override suspend fun discover(excluded: Set<Endpoint>): DiscoveryResult {
        if (!running.tryLock()) return DiscoveryResult(notice = "discovery_busy")
        try {
            val now = nowMillis()
            if (lastStart?.let { now - it < 30_000 } == true) return DiscoveryResult(notice = "discovery_cooldown")
            val network = try { backend.currentNetwork() } catch (_: Exception) { null }
                ?: return DiscoveryResult(notice = "discovery_no_wifi")
            lastStart = now
            val events = Channel<DiscoveryEvent>(16)
            val found = linkedMapOf<String, List<Endpoint>>()
            val seen = mutableSetOf<String>()
            var handle: Closeable? = null
            var notice: String? = null
            try {
                handle = backend.browse(network) { events.trySend(it) }
                withTimeoutOrNull(8000) {
                    while (found.values.flatten().distinct().size < 8) {
                        if (!isCurrent(network)) { notice = "discovery_network_changed"; break }
                        when (val event = events.receive()) {
                            DiscoveryEvent.NetworkChanged -> { notice = "discovery_network_changed"; break }
                            is DiscoveryEvent.Failed -> { notice = event.code; break }
                            is DiscoveryEvent.Lost -> { found.remove(event.key); seen.remove(event.key) }
                            is DiscoveryEvent.Found -> {
                                val service = event.service
                                if (service.type.trimEnd('.') != "_ene-companion._tcp" || service.key.length !in 1..256 || service.key in seen || seen.size >= 16) continue
                                seen.add(service.key)
                                val info = withTimeoutOrNull(2000) { service.resolve() } ?: continue
                                if (info.attributes.size > 8 || info.attributes.any { it.key.length > 32 || it.value.length > 128 } ||
                                    info.attributes["v"] != "1" || info.attributes["transport"] != "tls_v1") continue
                                found[service.key] = info.addresses.take(16).mapNotNull { discoveryEndpoint(it, info.port, network) }.filter { it !in excluded }.distinct()
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: DiscoveryFailure) { notice = error.code }
            catch (_: Exception) { notice = "discovery_unavailable" }
            finally {
                events.close()
                try { handle?.close() }
                catch (error: DiscoveryFailure) { notice = error.code }
                catch (_: Exception) { notice = "discovery_stop_failed" }
            }
            if (!isCurrent(network) || notice == "discovery_network_changed") return DiscoveryResult(notice = "discovery_network_changed")
            val endpoints = found.values.flatten().distinct().take(8)
            return DiscoveryResult(endpoints, network, notice ?: if (endpoints.isEmpty()) "discovery_no_candidates" else null)
        } finally { running.unlock() }
    }
}
