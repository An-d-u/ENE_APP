package dev.ene.companion.connection

import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** OS 조회가 취소를 무시해도 실제 작업은 하나, 대기열은 0개로 제한한다. */
internal class BoundedDnsLookup(
    private val timeoutMillis: Long = 3000,
    private val systemLookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : Closeable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
        { task -> Thread(task, "ene-companion-dns").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun lookup(host: String): List<InetAddress> {
        val future = try { executor.submit(Callable { systemLookup(host) }) }
        catch (_: RejectedExecutionException) { throw UnknownHostException("dns_busy") }
        val addresses = try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw UnknownHostException("pc_unreachable")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw UnknownHostException("connection_closed")
        } catch (_: ExecutionException) {
            throw UnknownHostException("pc_unreachable")
        }
        return addresses.filterIsInstance<Inet4Address>().distinct().take(8)
            .ifEmpty { throw UnknownHostException("unsupported_address_family") }
    }

    override fun close() { executor.shutdownNow() }

    companion object {
        // 연결 후보·클라이언트가 바뀌어도 실제 OS 조회의 전체 상한은 유지한다.
        val shared: BoundedDnsLookup by lazy { BoundedDnsLookup() }
    }
}
