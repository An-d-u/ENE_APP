package dev.ene.companion.connection

import android.content.Context
import android.net.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun androidPcDiscovery(context: Context): PcDiscovery = BoundedPcDiscovery(AndroidDiscoveryBackend(context.applicationContext)) {
    android.os.SystemClock.elapsedRealtime()
}

/** OS 콜백은 메인 큐로 모으고 이전 요청의 늦은 결과는 재사용하지 않는다. */
@Suppress("DEPRECATION")
internal class AndroidDiscoveryBackend(private val context: Context) : DiscoveryBackend {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var browseInFlight: Browse? = null
    private val resolverBusy = AtomicBoolean(false)

    override fun currentNetwork(): LanScope? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val prefixes = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull {
            if (it.address !is Inet4Address) null else ipv4Number(it.address.hostAddress.orEmpty())?.let { address -> Ipv4Prefix(address, it.prefixLength) }
        }.filter { it.length in 1..32 }.distinct().sortedWith(compareBy({ it.address }, { it.length }))
        return if (prefixes.isEmpty()) null else LanScope(network.networkHandle, prefixes)
    }

    override fun browse(network: LanScope, emit: (DiscoveryEvent) -> Unit): Closeable {
        if (browseInFlight != null) throw DiscoveryFailure("discovery_stop_failed")
        if (resolverBusy.get()) throw DiscoveryFailure("discovery_resolver_busy")
        if (currentNetwork() != network) throw DiscoveryFailure("discovery_network_changed")
        val browse = Browse(network, emit)
        browseInFlight = browse
        browse.start()
        return browse
    }

    private inner class Browse(private val scope: LanScope, private val emit: (DiscoveryEvent) -> Unit) : Closeable {
        private var live = true
        private var requested = false
        private var started = false
        private var stopRequests = 0
        private var callbackRegistered = false
        private var multicast: WifiManager.MulticastLock? = null
        private val networkCallback = object : ConnectivityManager.NetworkCallback() {
            private fun changed() { if (live && currentNetwork() != scope) emit(DiscoveryEvent.NetworkChanged) }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        }
        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { handler.post { started = true; if (!live) stop() } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post {
                requested = false
                if (browseInFlight === this@Browse) browseInFlight = null
                if (live) emit(DiscoveryEvent.Failed("discovery_start_failed"))
            } }
            override fun onDiscoveryStopped(type: String) { handler.post {
                requested = false; started = false
                if (browseInFlight === this@Browse) browseInFlight = null
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) { handler.post {
                if (live) emit(DiscoveryEvent.Failed("discovery_stop_failed"))
                if (stopRequests < 2) stop()
            } }
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (live) emit(DiscoveryEvent.Found(object : DiscoveryService {
                    override val key = info.serviceName.orEmpty()
                    override val type = info.serviceType.orEmpty()
                    override suspend fun resolve(): ResolvedService? {
                        if (!live || currentNetwork() != scope) throw DiscoveryFailure("discovery_network_changed")
                        if (Build.VERSION.SDK_INT >= 33) info.network = Network.fromNetworkHandle(scope.networkHandle)
                        return if (Build.VERSION.SDK_INT >= 34) resolveModern(info) else resolveLegacy(info)
                    }
                }))
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post { if (live) emit(DiscoveryEvent.Lost(info.serviceName.orEmpty())) } }
        }

        fun start() {
            try {
                connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback, handler)
                callbackRegistered = true
                if (Build.VERSION.SDK_INT < 33) {
                    multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("ene-companion-discovery").apply { setReferenceCounted(false); acquire() }
                }
                requested = true
                if (Build.VERSION.SDK_INT >= 33) nsd.discoverServices("_ene-companion._tcp.", NsdManager.PROTOCOL_DNS_SD,
                    Network.fromNetworkHandle(scope.networkHandle), executor, listener)
                else nsd.discoverServices("_ene-companion._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                requested = false
                if (browseInFlight === this) browseInFlight = null
                close()
                throw DiscoveryFailure("discovery_start_failed")
            }
        }

        private fun stop() {
            if (!requested || !started || stopRequests >= 2) return
            stopRequests++
            try { nsd.stopServiceDiscovery(listener) }
            catch (_: Exception) {
                if (stopRequests < 2) handler.post { stop() }
            }
        }

        override fun close() {
            if (!live) return
            live = false
            try {
                if (callbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
            } finally {
                callbackRegistered = false
                try { multicast?.let { if (it.isHeld) it.release() } }
                finally { multicast = null; stop() }
            }
        }
    }

    private fun result(info: NsdServiceInfo): ResolvedService? {
        val attributes = info.attributes
        if (attributes.size > 8 || attributes.any { it.key.length > 32 || it.value.size > 128 }) return null
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return ResolvedService(addresses.take(16).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }, info.port,
            attributes.mapValues { it.value.toString(Charsets.UTF_8) })
    }

    private suspend fun resolveLegacy(info: NsdServiceInfo): ResolvedService? = suspendCancellableCoroutine { continuation ->
        if (!resolverBusy.compareAndSet(false, true)) { continuation.resumeWithException(DiscoveryFailure("discovery_resolver_busy")); return@suspendCancellableCoroutine }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post { resolverBusy.set(false); if (continuation.isActive) continuation.resume(null) } }
            override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                resolverBusy.set(false)
                if (continuation.isActive) continuation.resume(runCatching { result(service) }.getOrNull())
            } }
        }
        try { nsd.resolveService(info, listener) }
        catch (_: Exception) { resolverBusy.set(false); if (continuation.isActive) continuation.resume(null) }
        // 구형 API에는 확실한 취소 수단이 없다. 완료 콜백 전에는 다음 해석을 시작하지 않는다.
    }

    @RequiresApi(34)
    private suspend fun resolveModern(info: NsdServiceInfo): ResolvedService? = suspendCancellableCoroutine { continuation ->
        if (!resolverBusy.compareAndSet(false, true)) { continuation.resumeWithException(DiscoveryFailure("discovery_resolver_busy")); return@suspendCancellableCoroutine }
        var value: ResolvedService? = null
        var unregisterRequested = false
        var failed = false
        lateinit var listener: NsdManager.ServiceInfoCallback
        fun unregister() {
            if (unregisterRequested || failed) return
            unregisterRequested = true
            try { nsd.unregisterServiceInfoCallback(listener) }
            catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(DiscoveryFailure("discovery_resolver_busy")) }
        }
        listener = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                failed = true; resolverBusy.set(false)
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onServiceUpdated(service: NsdServiceInfo) { if (!unregisterRequested) { value = runCatching { result(service) }.getOrNull(); unregister() } }
            override fun onServiceLost() { value = null; unregister() }
            override fun onServiceInfoCallbackUnregistered() { resolverBusy.set(false); if (continuation.isActive) continuation.resume(value) }
        }
        continuation.invokeOnCancellation { handler.post { unregister() } }
        try { nsd.registerServiceInfoCallback(info, executor, listener) }
        catch (_: Exception) { failed = true; resolverBusy.set(false); if (continuation.isActive) continuation.resume(null) }
    }
}
