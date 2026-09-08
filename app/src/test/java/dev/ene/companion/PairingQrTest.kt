package dev.ene.companion

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.Endpoint
import dev.ene.companion.connection.TrustedServer
import dev.ene.companion.pairing.PairingQr
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** QR 시험에는 예약 주소와 실행 중 생성한 일회용 비밀만 사용한다. */
class PairingQrTest {
    private val now = Instant.now()
    private val ca by lazy { TlsTestCertificates.ca() }
    private fun fixture(): JsonObject = buildJsonObject {
        put("protocol_version", 1)
        put("transport", "tls_v1")
        put("ca_certificate", TlsTestCertificates.encoded(ca.certificate))
        put("server_id", "00000000-0000-4000-8000-000000000001")
        put("pairing_id", "00000000-0000-4000-8000-000000000002")
        put("expires_at", now.plusSeconds(120).toString())
        put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        putJsonArray("addresses") {
            add(buildJsonObject { put("host", "192.0.2.10"); put("port", 8765) })
            add(buildJsonObject { put("host", "100.64.0.10"); put("port", 9000) })
        }
    }

    @Test fun validQrHasNormalizedFieldsAndDoesNotExposeSecretInDescription() {
        val raw = fixture()
        val qr = PairingQr.parse(raw.toString()) { now.toEpochMilli() }
        assertEquals("00000000-0000-4000-8000-000000000001", qr.serverId)
        assertEquals(listOf(Endpoint.parse("192.0.2.10", 8765), Endpoint.parse("100.64.0.10", 9000)), qr.addresses)
        assertFalse(qr.toString().contains(raw.getValue("secret").jsonPrimitive.content))
        assertEquals(now.plusSeconds(120), qr.expiresAt)
        assertEquals(TrustedServer.TRANSPORT, qr.transport)
        assertEquals(raw.getValue("ca_certificate").jsonPrimitive.content, qr.trust.caCertificate)
    }

    @Test fun sharedTlsQrCasesRejectLegacyAndUnknownTransport() {
        val cases = javaClass.getResourceAsStream("/tls_cases.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        for (case in cases.getValue("qr_cases").jsonArray) {
            val fields = case.jsonObject
            val raw = fixture().toMutableMap()
            when (fields.getValue("mutation").jsonPrimitive.content) {
                "none" -> Unit
                "missing-transport" -> raw.remove("transport")
                "wrong-transport" -> raw["transport"] = JsonPrimitive("unknown")
                "missing-ca" -> raw.remove("ca_certificate")
                "eight-addresses", "nine-addresses" -> {
                    val count = if (fields.getValue("mutation").jsonPrimitive.content == "eight-addresses") 8 else 9
                    raw["addresses"] = buildJsonArray { repeat(count) { add(buildJsonObject { put("host", "192.0.2.${it + 1}"); put("port", 8765) }) } }
                }
                else -> error("정의되지 않은 공통 QR 사례")
            }
            val accepted = try { PairingQr.parse(JsonObject(raw).toString()) { now.toEpochMilli() }; true } catch (_: ConnectionException) { false }
            assertEquals(fields.getValue("id").jsonPrimitive.content, fields.getValue("accepted").jsonPrimitive.boolean, accepted)
        }
    }

    @Test fun qrAnchorUsesLiveClockAndRejectsOtherPcCertificate() {
        var time = now.toEpochMilli()
        val qr = PairingQr.parse(fixture().toString(), clock = { time })
        time = ca.certificate.notAfter.time
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { qr.trust.validate() }.code)
        val wrong = JsonObject(fixture() + ("server_id" to JsonPrimitive("00000000-0000-4000-8000-000000000003")))
        assertEquals("tls_identity_invalid", assertThrows(ConnectionException::class.java) { PairingQr.parse(wrong.toString()) { now.toEpochMilli() } }.code)
    }

    @Test fun invalidVersionTypeAndExpiredQrAreDistinguished() {
        val raw = fixture()
        val wrong = JsonObject(raw + ("protocol_version" to JsonPrimitive(2)))
        assertEquals("unsupported_version", assertThrows(ConnectionException::class.java) { PairingQr.parse(wrong.toString()) { now.toEpochMilli() } }.code)
        val stringVersion = JsonObject(raw + ("protocol_version" to JsonPrimitive("1")))
        assertEquals("invalid_qr", assertThrows(ConnectionException::class.java) { PairingQr.parse(stringVersion.toString()) { now.toEpochMilli() } }.code)
        assertEquals("pairing_expired", assertThrows(ConnectionException::class.java) { PairingQr.parse(raw.toString()) { now.plusSeconds(121).toEpochMilli() } }.code)
    }

    @Test fun malformedQrNeverBecomesNavigationOrCredentials() {
        val raw = fixture()
        for ((key, value) in listOf(
            "server_id" to JsonPrimitive("not-an-id"), "secret" to JsonPrimitive("invalid"),
            "expires_at" to JsonPrimitive("2026-01-01T01:02:00+01:00"),
            "addresses" to JsonArray(emptyList()), "addresses" to JsonArray(List(9) { raw.getValue("addresses").jsonArray[0] }),
        )) {
            assertThrows(key, ConnectionException::class.java) { PairingQr.parse(JsonObject(raw + (key to value)).toString()) { now.toEpochMilli() } }
        }
        for (invalid in listOf("https://example.invalid/", "x".repeat(2049), "[".repeat(30) + "0" + "]".repeat(30), "{}", "\uD800")) {
            assertThrows(ConnectionException::class.java) { PairingQr.parse(invalid) { now.toEpochMilli() } }
        }
    }

    @Test fun qrAddressesRejectLoopbackAndUnsupportedFamiliesButManualNamesRemainAvailable() {
        for (host in listOf("0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255", "::1", "pc.example.invalid")) {
            val raw = JsonObject(fixture() + ("addresses" to buildJsonArray { add(buildJsonObject { put("host", host); put("port", 8765) }) }))
            assertThrows(host, ConnectionException::class.java) { PairingQr.parse(raw.toString()) { now.toEpochMilli() } }
        }
        assertEquals("pc.example.invalid", Endpoint.parse(" PC.Example.Invalid ", 8123).host)
        for (host in listOf("", "http://pc", "pc/path", "user@pc", "pc?key=x", "pc#part", "pc..", "::1")) {
            assertThrows(host, ConnectionException::class.java) { Endpoint.parse(host, 8765) }
        }
        for (port in listOf(0, -1, 65536)) assertThrows(ConnectionException::class.java) { Endpoint.parse("192.0.2.10", port) }
    }
}
