package dev.ene.companion

import okhttp3.tls.HeldCertificate
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/** CA의 keyCertSign 확장은 JDK 도구로 만들며 키는 시험 소유 임시 파일에서만 다룬다. */
object TlsTestCertificates {
    const val SERVER_ID = "00000000-0000-4000-8000-000000000001"
    fun encoded(certificate: X509Certificate): String = Base64.getEncoder().encodeToString(certificate.encoded)

    fun ca(serverId: String = SERVER_ID, pathLength: Int = 0, usage: String? = "keyCertSign", curve: String = "secp256r1"): HeldCertificate {
        val folder = Files.createTempDirectory("ene-tls-test-")
        val file = folder.resolve("ca.p12")
        val password = Base64.getUrlEncoder().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) }).toCharArray()
        try {
            val suffix = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""
            val command = mutableListOf(
                Path.of(System.getProperty("java.home"), "bin", "keytool$suffix").toString(),
                "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", curve,
                "-sigalg", "SHA256withECDSA", "-dname", "CN=ENE CA $serverId",
                "-startdate", DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now().minusSeconds(300)),
                "-validity", "3650", "-ext", "BC:critical=ca:true,pathlen:$pathLength",
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", String(password), "-noprompt",
            )
            if (usage != null) command.addAll(listOf("-ext", "KU:critical=$usage"))
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "가상 CA 생성 실패" }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(file).use { store.load(it, password) }
            val certificate = store.getCertificate("ca") as X509Certificate
            return HeldCertificate(KeyPair(certificate.publicKey, store.getKey("ca", password) as PrivateKey), certificate)
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(file)
            Files.deleteIfExists(folder)
        }
    }

    fun leaf(ca: HeldCertificate, hostname: String = "ene-$SERVER_ID.invalid", before: Long = System.currentTimeMillis() - 300_000, after: Long = System.currentTimeMillis() + 86_400_000): HeldCertificate =
        HeldCertificate.Builder().ecdsa256().commonName(hostname).addSubjectAlternativeName(hostname)
            .validityInterval(before, after).signedBy(ca).build()
}
