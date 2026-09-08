package dev.ene.companion.connection

import dev.ene.companion.protocol.Ping
import java.util.UUID
import kotlin.random.Random

class Heartbeat(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val newNonce: () -> String = { UUID.randomUUID().toString() },
) {
    private var nextPing = nowMillis() + 15_000
    private var nonce: String? = null
    private var deadline = 0L

    fun tick(): Ping? {
        val now = nowMillis()
        if (nonce != null) {
            if (now >= deadline) throw ConnectionException("heartbeat_timeout")
            return null
        }
        if (now < nextPing) return null
        val created = newNonce()
        nonce = created
        deadline = now + 10_000
        return Ping(created)
    }

    fun pong(value: String): Boolean {
        if (nonce == null || nonce != value || nowMillis() >= deadline) return false
        nonce = null
        nextPing = nowMillis() + 15_000
        return true
    }
}

class RetryBackoff(private val random: () -> Double = { Random.nextDouble() }) {
    private var attempt = 0
    private val intervals = longArrayOf(1000, 2000, 4000, 8000, 16000, 30000)

    fun nextMillis(): Long {
        val base = intervals[attempt.coerceAtMost(intervals.lastIndex)]
        if (attempt < intervals.lastIndex) attempt++
        return base + (base * 0.2 * random().coerceIn(0.0, 1.0)).toLong()
    }

    fun reset() { attempt = 0 }
}
