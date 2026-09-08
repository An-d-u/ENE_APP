package dev.ene.companion.connection

import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.security.AlgorithmParameters
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64
import javax.security.auth.x500.X500Principal

/** QR에서 직접 받은 단일 CA만 보관한다. 서버 이름은 라우팅 주소와 분리한다. */
class TrustedServer private constructor(
    val serverId: String,
    val caCertificate: String,
    internal val certificate: X509Certificate,
    private val clock: () -> Long,
) {
    val hostname: String = "ene-$serverId.invalid"
    val expiresAtMillis: Long get() = certificate.notAfter.time
    internal fun remainingMillis(): Long = expiresAtMillis - clock()

    fun validate() {
        val now = clock()
        if (now < certificate.notBefore.time) throw ConnectionException("tls_clock_invalid")
        if (now >= certificate.notAfter.time) throw ConnectionException("tls_expired")
    }

    override fun toString(): String = "TrustedServer()"

    companion object {
        const val TRANSPORT = "tls_v1"
        const val MAX_CA_BYTES = 768
        private val p256: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        fun parse(serverId: String, caCertificate: String, clock: () -> Long = System::currentTimeMillis): TrustedServer = try {
            val id = ProtocolCodec.uuid(JsonPrimitive(serverId))
            if (caCertificate.isEmpty() || caCertificate.length > 1024) invalid()
            val der = Base64.getDecoder().decode(caCertificate)
            if (der.size > MAX_CA_BYTES || Base64.getEncoder().encodeToString(der) != caCertificate) invalid()
            val input = ByteArrayInputStream(der)
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            if (input.available() != 0 || !certificate.encoded.contentEquals(der)) invalid()
            if (certificate.sigAlgOID != "1.2.840.10045.4.3.2" || certificate.basicConstraints != 0) invalid()
            if (certificate.subjectX500Principal != X500Principal("CN=ENE CA $id") || certificate.issuerX500Principal != certificate.subjectX500Principal) invalid()
            val critical = certificate.criticalExtensionOIDs ?: emptySet()
            if ("2.5.29.19" !in critical || critical.any { it !in setOf("2.5.29.19", "2.5.29.15") }) invalid()
            val usage = certificate.keyUsage ?: invalid()
            if (usage.size <= 5 || !usage[5]) invalid()
            val key = certificate.publicKey as? ECPublicKey ?: invalid()
            val parameters = key.params
            if (parameters.curve != p256.curve || parameters.generator != p256.generator || parameters.order != p256.order || parameters.cofactor != p256.cofactor) invalid()
            certificate.verify(key)
            TrustedServer(id, caCertificate, certificate, clock).also { it.validate() }
        } catch (error: ConnectionException) {
            throw error
        } catch (_: Exception) {
            throw ConnectionException("tls_identity_invalid")
        }

        private fun invalid(): Nothing = throw ConnectionException("tls_identity_invalid")
    }
}
