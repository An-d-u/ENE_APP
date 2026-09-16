package dev.ene.companion.pairing

import java.util.concurrent.atomic.AtomicBoolean
import dev.ene.companion.connection.ConnectionException

/** 잘못된 QR은 무시하고 유효한 첫 결과만 수락한다. 원문은 보관하지 않는다. */
class QrScanGate {
    var errorCode: String? = null
        private set
    private val closed = AtomicBoolean()
    fun accept(raw: String): Boolean {
        if (closed.get()) return false
        try { PairingQr.parse(raw) } catch (error: ConnectionException) { errorCode = error.code; return false }
        errorCode = null
        return closed.compareAndSet(false, true)
    }
    fun close() { closed.set(true) }
}
