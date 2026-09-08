package dev.ene.companion

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.TrustedServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TrustedServerTest {
    private val serverId = TlsTestCertificates.SERVER_ID

    @Test fun sharedAnchorContractCasesAreEnforced() {
        val ca = TlsTestCertificates.ca()
        val valid = TlsTestCertificates.encoded(ca.certificate)
        val cases = javaClass.getResourceAsStream("/tls_cases.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        for (case in cases.getValue("anchor_cases").jsonArray) {
            val fields = case.jsonObject
            var id = serverId
            val encoded = when (fields.getValue("mutation").jsonPrimitive.content) {
                "none" -> valid
                "wrong-id" -> { id = "00000000-0000-4000-8000-000000000002"; valid }
                "extra-der" -> Base64.getEncoder().encodeToString(ca.certificate.encoded + byteArrayOf(0))
                "noncanonical" -> "$valid\n"
                "oversize" -> Base64.getEncoder().encodeToString(ByteArray(769))
                "leaf-as-ca" -> TlsTestCertificates.encoded(TlsTestCertificates.leaf(ca).certificate)
                "garbage" -> "garbage"
                "missing" -> ""
                else -> error("정의되지 않은 공통 인증서 사례")
            }
            val accepted = try { TrustedServer.parse(id, encoded); true } catch (_: ConnectionException) { false }
            assertEquals(fields.getValue("id").jsonPrimitive.content, fields.getValue("accepted").jsonPrimitive.boolean, accepted)
        }
    }

    @Test fun validAnchorIsBoundToFixedServerNameAndRedactsItsValue() {
        val ca = TlsTestCertificates.ca()
        val encoded = TlsTestCertificates.encoded(ca.certificate)
        val trusted = TrustedServer.parse(serverId, encoded)
        assertEquals(serverId, trusted.serverId)
        assertEquals("ene-$serverId.invalid", trusted.hostname)
        assertEquals(encoded, trusted.caCertificate)
        assertTrue(ca.certificate.encoded.size <= 768)
        assertFalse(trusted.toString().contains(encoded))
        assertFalse(trusted.toString().contains(serverId))
        trusted.validate()
    }

    @Test fun rejectsChangedSignatureExtraDerNoncanonicalBase64AndOversize() {
        val ca = TlsTestCertificates.ca()
        val der = ca.certificate.encoded
        val valid = TlsTestCertificates.encoded(ca.certificate)
        val changed = der.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (encoded in listOf(Base64.getEncoder().encodeToString(changed), Base64.getEncoder().encodeToString(der + byteArrayOf(0)), "$valid\n", Base64.getEncoder().encodeToString(ByteArray(769)), "", "garbage")) {
            assertEquals("tls_identity_invalid", assertThrows(ConnectionException::class.java) { TrustedServer.parse(serverId, encoded) }.code)
        }
    }

    @Test fun rejectsDifferentIdentityOrServerLeafAsAnchor() {
        val ca = TlsTestCertificates.ca()
        val leaf = TlsTestCertificates.leaf(ca)
        assertThrows(ConnectionException::class.java) { TrustedServer.parse("00000000-0000-4000-8000-000000000002", TlsTestCertificates.encoded(ca.certificate)) }
        assertThrows(ConnectionException::class.java) { TrustedServer.parse(serverId, TlsTestCertificates.encoded(leaf.certificate)) }
    }

    @Test fun rejectsMissingSigningUsageExcessPathLengthAndDifferentCurve() {
        for (ca in listOf(TlsTestCertificates.ca(usage = null), TlsTestCertificates.ca(usage = "digitalSignature"), TlsTestCertificates.ca(pathLength = 1), TlsTestCertificates.ca(curve = "secp384r1"))) {
            assertThrows(ConnectionException::class.java) { TrustedServer.parse(serverId, TlsTestCertificates.encoded(ca.certificate)) }
        }
    }

    @Test fun validityIsCheckedOnParseAndAgainAfterClockChanges() {
        val ca = TlsTestCertificates.ca()
        var now = System.currentTimeMillis()
        val trusted = TrustedServer.parse(serverId, TlsTestCertificates.encoded(ca.certificate), clock = { now })
        now = ca.certificate.notAfter.time
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { trusted.validate() }.code)
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { TrustedServer.parse(serverId, trusted.caCertificate, clock = { now }) }.code)
        now = ca.certificate.notBefore.time - 1
        assertEquals("tls_clock_invalid", assertThrows(ConnectionException::class.java) { trusted.validate() }.code)
    }
}
