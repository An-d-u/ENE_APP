package dev.ene.companion

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.storage.DeviceCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class DeviceCredentialsTest {
    private val ca by lazy { TlsTestCertificates.ca() }
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun credentials() = DeviceCredentials.create(TlsTestCertificates.SERVER_ID, "00000000-0000-4000-8000-000000000002", 1, token, TlsTestCertificates.encoded(ca.certificate))

    @Test fun singleVersionedRecordBindsTokenToPcCaAndRedactsDescription() {
        val expected = credentials()
        val raw = Json.encodeToString(expected)
        val fields = Json.parseToJsonElement(raw).jsonObject
        assertEquals(2, fields.getValue("recordVersion").jsonPrimitive.int)
        assertEquals("tls_v1", fields.getValue("transport").jsonPrimitive.content)
        assertEquals(expected, DeviceCredentials.parse(raw))
        assertEquals(expected.caCertificate, expected.trustedServer().caCertificate)
        assertFalse(expected.toString().contains(token))
        assertFalse(expected.toString().contains(expected.caCertificate))
    }

    @Test fun legacyMissingCaAndUnknownVersionsRequirePairingWithoutUpgrade() {
        val valid = Json.parseToJsonElement(Json.encodeToString(credentials())).jsonObject
        for (bad in listOf(
            JsonObject(valid - setOf("recordVersion", "transport", "caCertificate")),
            JsonObject(valid - "caCertificate"),
            JsonObject(valid + ("recordVersion" to JsonPrimitive(3))),
            JsonObject(valid + ("transport" to JsonPrimitive("unknown"))),
        )) {
            assertEquals("tls_repair_required", assertThrows(ConnectionException::class.java) { DeviceCredentials.parse(bad.toString()) }.code)
        }
    }

    @Test fun restoreRechecksExpiryClockAndPcBinding() {
        val expected = credentials()
        val raw = Json.encodeToString(expected)
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { DeviceCredentials.parse(raw, clock = { ca.certificate.notAfter.time }) }.code)
        assertEquals("tls_clock_invalid", assertThrows(ConnectionException::class.java) { DeviceCredentials.parse(raw, clock = { ca.certificate.notBefore.time - 1 }) }.code)
        val fields = Json.parseToJsonElement(raw).jsonObject
        val changed = JsonObject(fields + ("serverId" to JsonPrimitive("00000000-0000-4000-8000-000000000003")))
        assertEquals("tls_identity_invalid", assertThrows(ConnectionException::class.java) { DeviceCredentials.parse(changed.toString()) }.code)
    }

    @Test fun malformedCredentialValuesAreNotRestored() {
        val valid = Json.parseToJsonElement(Json.encodeToString(credentials())).jsonObject
        for ((key, value) in listOf("token" to JsonPrimitive("invalid"), "generation" to JsonPrimitive(0), "deviceId" to JsonPrimitive("invalid"))) {
            assertThrows(ConnectionException::class.java) { DeviceCredentials.parse(JsonObject(valid + (key to value)).toString()) }
        }
    }
}
