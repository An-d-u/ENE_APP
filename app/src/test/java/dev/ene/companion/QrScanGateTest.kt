package dev.ene.companion

import dev.ene.companion.pairing.QrScanGate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class QrScanGateTest {
    private fun qr() = buildJsonObject {
        put("protocol_version", 1); put("transport", "tls_v1"); put("server_id", ConnectionRepositoryTest.serverId)
        put("pairing_id", ConnectionRepositoryTest.pairingId); put("secret", ConnectionRepositoryTest.secret())
        put("ca_certificate", TlsTestCertificates.encoded(ConnectionRepositoryTest.ca.certificate))
        put("expires_at", Instant.now().plusSeconds(120).toString())
        putJsonArray("addresses") { add(buildJsonObject { put("host", "192.0.2.1"); put("port", 8765) }) }
    }.toString()

    @Test fun invalidQrDoesNotConsumeScanButFirstValidOneDoes() {
        val gate = QrScanGate()
        assertFalse(gate.accept("일반 가상 QR"))
        assertEquals("invalid_qr", gate.errorCode)
        assertTrue(gate.accept(qr()))
        assertNull(gate.errorCode)
        assertFalse(gate.accept(qr()))
    }

    @Test fun disposedScannerRejectsLateResult() {
        val gate = QrScanGate()
        gate.close()
        assertFalse(gate.accept(qr()))
    }

    @Test fun expiredQrIsExplainedWithoutConsumingNextScan() {
        val fields = Json.parseToJsonElement(qr()).jsonObject
        val expired = JsonObject(fields + ("expires_at" to JsonPrimitive("2020-01-01T00:00:00Z"))).toString()
        val gate = QrScanGate()
        assertFalse(gate.accept(expired))
        assertEquals("pairing_expired", gate.errorCode)
        assertTrue(gate.accept(qr()))
    }
}
