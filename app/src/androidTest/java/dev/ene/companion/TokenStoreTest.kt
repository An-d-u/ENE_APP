package dev.ene.companion

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.Endpoint
import dev.ene.companion.storage.ConnectionProfile
import dev.ene.companion.storage.ConnectionSettingsStore
import dev.ene.companion.storage.DeviceCredentials
import dev.ene.companion.storage.TokenStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.serialization.json.*

/** 실제 Android Keystore는 JVM 대역으로 통과 처리하지 않고 기기에서 확인한다. */
@RunWith(AndroidJUnit4::class)
class TokenStoreTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var alias: String
    private lateinit var ca: X509Certificate

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        alias = "ene_companion_test_" + UUID.randomUUID()
        directory = File(context.noBackupFilesDir, "token-store-test-" + UUID.randomUUID())
        check(directory.mkdirs())
        ca = InstrumentationRegistry.getInstrumentation().context.assets.open("ca.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    @After fun cleanup() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        check(directory.name.startsWith("token-store-test-"))
        directory.deleteRecursively()
    }

    private fun credentials(): DeviceCredentials = DeviceCredentials.create(
        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", 1,
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }),
        Base64.getEncoder().encodeToString(ca.encoded),
    )

    @Test fun encryptsInNoBackupDirectoryAndRestoresWithoutPlainToken() {
        val expected = credentials()
        val store = TokenStore(context, directory, alias)
        assertNull(store.load())
        store.save(expected)
        assertEquals(expected, TokenStore(context, directory, alias).load())
        for (file in requireNotNull(directory.listFiles())) {
            assertFalse(file.readText().contains(expected.token))
            assertFalse(file.readText().contains(expected.caCertificate))
        }
        assertFalse(expected.toString().contains(expected.token))
        store.clear()
        assertNull(store.load())
    }

    @Test fun missingKeyAndCorruptCiphertextRequirePairingAgain() {
        val store = TokenStore(context, directory, alias)
        store.save(credentials())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertEquals("registration_lost", assertThrows(ConnectionException::class.java) { store.load() }.code)
        store.save(credentials())
        val file = File(directory, "registration.enc")
        file.writeText("손상된 가상 암호문", Charsets.UTF_8)
        assertEquals("registration_lost", assertThrows(ConnectionException::class.java) { store.load() }.code)
    }

    @Test fun expiredCaBlocksRestoreAndSaveWithoutChangingEncryptedRecord() {
        var now = System.currentTimeMillis()
        val store = TokenStore(context, directory, alias, clock = { now })
        val expected = credentials()
        store.save(expected)
        val file = File(directory, "registration.enc")
        val before = file.readBytes()
        now = ca.notAfter.time
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { store.load() }.code)
        assertEquals("tls_expired", assertThrows(ConnectionException::class.java) { store.save(expected) }.code)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun validOldEncryptionEnvelopeDoesNotUpgradeLegacyRegistration() {
        val store = TokenStore(context, directory, alias)
        val expected = credentials()
        store.save(expected)
        val old = buildJsonObject {
            put("serverId", expected.serverId); put("deviceId", expected.deviceId)
            put("generation", expected.generation); put("token", expected.token)
        }.toString().toByteArray(Charsets.UTF_8)
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD("ene_companion_token_v1".toByteArray(Charsets.UTF_8))
        }
        val encrypted = try { cipher.doFinal(old) } finally { old.fill(0) }
        val envelope = buildJsonObject {
            put("version", 1); put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            put("ciphertext", Base64.getEncoder().encodeToString(encrypted))
        }.toString()
        val file = File(directory, "registration.enc")
        file.writeText(envelope, Charsets.UTF_8)
        assertEquals("tls_repair_required", assertThrows(ConnectionException::class.java) { store.load() }.code)
        assertEquals(envelope, file.readText())
    }

    @Test fun backupEligibleDirectoriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TokenStore(context, context.filesDir, alias) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionSettingsStore(context, context.filesDir) }
    }

    @Test fun settingsContainOnlyPcIdentityAndAddresses() {
        val store = ConnectionSettingsStore(context, directory)
        val expected = ConnectionProfile("00000000-0000-4000-8000-000000000001", listOf(Endpoint.parse("192.0.2.10", 8765)))
        assertNull(store.load())
        store.save(expected)
        assertEquals(expected, store.load())
        val raw = File(directory, "connection.json").readText()
        assertFalse(raw.contains("token"))
        assertFalse(raw.contains("messages"))
        store.clear()
        assertNull(store.load())
    }
}
