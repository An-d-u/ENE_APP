package dev.ene.companion.pairing

import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.Endpoint
import dev.ene.companion.connection.TrustedServer
import dev.ene.companion.protocol.ProtocolCodec
import dev.ene.companion.protocol.ProtocolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.Collections

class PairingQr private constructor(
    val serverId: String,
    val pairingId: String,
    val secret: String,
    val expiresAt: Instant,
    val addresses: List<Endpoint>,
    val trust: TrustedServer,
) {
    val transport: String get() = TrustedServer.TRANSPORT
    override fun toString(): String = "PairingQr"

    companion object {
        fun parse(raw: String, clock: () -> Long = System::currentTimeMillis): PairingQr = try {
            val value = ProtocolCodec.readObject(raw, 2048)
            if (ProtocolCodec.integer(value["protocol_version"]) != 1L) throw ConnectionException("unsupported_version")
            val serverId = ProtocolCodec.uuid(value["server_id"])
            if (value["transport"] == null || ProtocolCodec.text(value["transport"]) != TrustedServer.TRANSPORT) throw ConnectionException("unsupported_transport")
            val trust = TrustedServer.parse(serverId, ProtocolCodec.text(value["ca_certificate"]), clock)
            val pairingId = ProtocolCodec.uuid(value["pairing_id"])
            val secret = ProtocolCodec.credential(value["secret"])
            val expiresAt = Instant.parse(ProtocolCodec.utc(value["expires_at"]))
            if (!expiresAt.isAfter(Instant.ofEpochMilli(clock()))) throw ConnectionException("pairing_expired")
            val addresses = value["addresses"] as? JsonArray ?: throw ProtocolException()
            if (addresses.size !in 1..8) throw ProtocolException()
            val endpoints = addresses.map { item ->
                val fields = item as? JsonObject ?: throw ProtocolException()
                val endpoint = Endpoint.parse(ProtocolCodec.text(fields["host"]), ProtocolCodec.integer(fields["port"], 1, 65535).toInt())
                if (!endpoint.isQrAddress()) throw ProtocolException()
                endpoint
            }.distinct()
            PairingQr(serverId, pairingId, secret, expiresAt, Collections.unmodifiableList(endpoints), trust)
        } catch (error: ConnectionException) {
            throw error
        } catch (_: IllegalArgumentException) {
            throw ConnectionException("invalid_qr")
        } catch (_: java.time.DateTimeException) {
            throw ConnectionException("invalid_qr")
        }
    }
}
