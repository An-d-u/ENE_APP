package dev.ene.companion

import dev.ene.companion.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class PcDiscoveryTest {
    private val network = LanScope(1, listOf(Ipv4Prefix(0xc633640aL, 24)))
    private class Backend(var network: LanScope?) : DiscoveryBackend {
        var emit: ((DiscoveryEvent) -> Unit)? = null
        var starts = 0
        var stops = 0
        var stopFails = false
        override fun currentNetwork() = network
        override fun browse(network: LanScope, emit: (DiscoveryEvent) -> Unit): Closeable {
            starts++; this.emit = emit
            return Closeable { stops++; if (stopFails) throw DiscoveryFailure("discovery_stop_failed") }
        }
    }
    private fun service(host: String, resolve: (suspend () -> ResolvedService?)? = null) = object : DiscoveryService {
        override val key = host
        override val type = "_ene-companion._tcp."
        override suspend fun resolve() = resolve?.invoke() ?: ResolvedService(listOf(host), 8765, mapOf("v" to "1", "transport" to "tls_v1"))
    }

    @Test fun ipv4ScopeRejectsNamesBroadcastAndUnrelatedNetworks() {
        for (host in listOf("host.local", "::1", "0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255", "198.51.100.255", "198.51.101.10")) {
            assertNull(host, discoveryEndpoint(host, 8765, network))
        }
        assertEquals(Endpoint.parse("198.51.100.41", 8765), discoveryEndpoint("198.51.100.41", 8765, network))
        assertNull(discoveryEndpoint("198.51.100.41", 0, network))
        assertNull(discoveryEndpoint("198.51.100.41", 8765, network.copy(prefixes = listOf(Ipv4Prefix(0, 0)))))
        assertTrue(belongsToPrefix(0xc633642bL, Ipv4Prefix(0xc633642aL, 31)))
    }

    @Test fun browseEndsAtEightSecondsAndStartCooldownIsThirtySeconds() = runTest {
        val backend = Backend(network)
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        val result = async { discovery.discover(emptySet()) }; runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.41")))
        advanceTimeBy(8000); runCurrent()
        assertEquals(listOf(Endpoint.parse("198.51.100.41", 8765)), result.await().endpoints)
        assertEquals(1, backend.stops)
        assertEquals("discovery_cooldown", discovery.discover(emptySet()).notice)
        advanceTimeBy(22_000)
        val again = async { discovery.discover(emptySet()) }; runCurrent()
        assertEquals(2, backend.starts)
        again.cancelAndJoin()
        assertEquals(2, backend.stops)
    }

    @Test fun resultCapDeduplicationAndLostServiceAreEnforced() = runTest {
        val backend = Backend(network)
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        val excluded = Endpoint.parse("198.51.100.41", 8765)
        val result = async { discovery.discover(setOf(excluded)) }; runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service(excluded.host))); runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.42"))); runCurrent()
        backend.emit!!(DiscoveryEvent.Lost("198.51.100.42")); runCurrent()
        for (i in 43..60) { backend.emit!!(DiscoveryEvent.Found(service("198.51.100.$i"))); runCurrent() }
        assertEquals(8, result.await().endpoints.size)
        assertTrue(result.await().endpoints.none { it.host in listOf("198.51.100.41", "198.51.100.42") })
    }

    @Test fun hungResolutionIsBoundedAndCancelledCallbacksCannotPublish() = runTest {
        val backend = Backend(network)
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        val result = async { discovery.discover(emptySet()) }; runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.41") { awaitCancellation() })); runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.42")))
        advanceTimeBy(2000); runCurrent()
        backend.network = network.copy(networkHandle = 2)
        backend.emit!!(DiscoveryEvent.NetworkChanged)
        runCurrent()
        assertTrue(result.await().endpoints.isEmpty())
        assertEquals("discovery_network_changed", result.await().notice)
        assertEquals(1, backend.stops)
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.43")))
        assertTrue(result.await().endpoints.isEmpty())
    }

    @Test fun invalidMetadataAndCleanupFailuresRemainLocalNotices() = runTest {
        val backend = Backend(network).apply { stopFails = true }
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        val result = async { discovery.discover(emptySet()) }; runCurrent()
        backend.emit!!(DiscoveryEvent.Found(service("198.51.100.41") { ResolvedService(listOf("198.51.100.41"), 8765, mapOf("v" to "2")) }))
        advanceTimeBy(8000); runCurrent()
        assertTrue(result.await().endpoints.isEmpty())
        assertEquals("discovery_stop_failed", result.await().notice)
    }

    @Test fun absentWifiAndConcurrentAttemptsNeverStartExtraBrowsers() = runTest {
        val backend = Backend(null)
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        assertEquals("discovery_no_wifi", discovery.discover(emptySet()).notice)
        assertEquals(0, backend.starts)
        backend.network = network
        val first = async { discovery.discover(emptySet()) }; runCurrent()
        assertEquals("discovery_busy", discovery.discover(emptySet()).notice)
        assertEquals(1, backend.starts)
        first.cancelAndJoin()
        assertEquals(1, backend.stops)
    }

    @Test fun startFailureAndLegacyResolverBusyDoNotEscapeIntoConnectionFailures() = runTest {
        for (code in listOf("discovery_start_failed", "discovery_resolver_busy")) {
            val backend = object : DiscoveryBackend {
                override fun currentNetwork() = network
                override fun browse(network: LanScope, emit: (DiscoveryEvent) -> Unit): Closeable = throw DiscoveryFailure(code)
            }
            assertEquals(code, BoundedPcDiscovery(backend) { testScheduler.currentTime }.discover(emptySet()).notice)
        }
    }

    @Test fun excessiveCallbacksHaveABoundedResolutionBudget() = runTest {
        val backend = Backend(network)
        val discovery = BoundedPcDiscovery(backend) { testScheduler.currentTime }
        var resolutions = 0
        val result = async { discovery.discover(emptySet()) }; runCurrent()
        repeat(1000) { index ->
            backend.emit!!(DiscoveryEvent.Found(service("synthetic-$index") { resolutions++; ResolvedService(emptyList(), 8765, emptyMap()) }))
        }
        advanceTimeBy(8000); runCurrent()
        assertEquals(16, resolutions)
        assertTrue(result.await().endpoints.isEmpty())
    }
}
