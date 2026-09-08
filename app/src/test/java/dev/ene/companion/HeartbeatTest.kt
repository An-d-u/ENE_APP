package dev.ene.companion

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.Heartbeat
import dev.ene.companion.connection.RetryBackoff
import org.junit.Assert.*
import org.junit.Test

class HeartbeatTest {
    @Test fun onlyMatchingPongBeforeTenSecondsClearsPendingPing() {
        var now = 0L
        val heartbeat = Heartbeat(nowMillis = { now })
        assertNull(heartbeat.tick())
        now = 15000
        val ping = requireNotNull(heartbeat.tick())
        assertFalse(heartbeat.pong("00000000-0000-4000-8000-000000000001"))
        now = 24999
        assertNull(heartbeat.tick())
        assertTrue(heartbeat.pong(ping.nonce))
        assertFalse(heartbeat.pong(ping.nonce))
        now += 14999
        assertNull(heartbeat.tick())
        now += 1
        assertNotNull(heartbeat.tick())
    }

    @Test fun lateOrOldPongDoesNotRecoverDeadConnection() {
        var now = 0L
        val heartbeat = Heartbeat(nowMillis = { now })
        now = 15000
        val ping = requireNotNull(heartbeat.tick())
        now = 25000
        assertFalse(heartbeat.pong(ping.nonce))
        assertEquals("heartbeat_timeout", assertThrows(ConnectionException::class.java) { heartbeat.tick() }.code)
    }

    @Test fun retryBackoffCapsAtThirtySecondsPlusJitterAndResets() {
        val backoff = RetryBackoff(random = { 0.0 })
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), List(7) { backoff.nextMillis() })
        backoff.reset()
        assertEquals(1000L, backoff.nextMillis())
        val jittered = RetryBackoff(random = { 0.5 })
        assertEquals(1100L, jittered.nextMillis())
        repeat(10) { assertTrue(jittered.nextMillis() in 1000L..36000L) }
    }
}
