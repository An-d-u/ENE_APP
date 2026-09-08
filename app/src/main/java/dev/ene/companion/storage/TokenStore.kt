package dev.ene.companion.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.TrustedServer
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
@ConsistentCopyVisibility
data class DeviceCredentials private constructor(
    val recordVersion: Int,
    val transport: String,
    val serverId: String,
    val deviceId: String,
    val generation: Long,
    val token: String,
    val caCertificate: String,
) {
    override fun toString(): String = "DeviceCredentials"

    fun trustedServer(clock: () -> Long = System::currentTimeMillis): TrustedServer {
        if (recordVersion != 2 || transport != TrustedServer.TRANSPORT) throw ConnectionException("tls_repair_required")
        return TrustedServer.parse(serverId, caCertificate, clock)
    }

    companion object {
        fun create(serverId: String, deviceId: String, generation: Long, token: String, caCertificate: String, clock: () -> Long = System::currentTimeMillis): DeviceCredentials = try {
            DeviceCredentials(2, TrustedServer.TRANSPORT, ProtocolCodec.uuid(JsonPrimitive(serverId)), ProtocolCodec.uuid(JsonPrimitive(deviceId)),
                ProtocolCodec.integer(JsonPrimitive(generation), 1), ProtocolCodec.credential(JsonPrimitive(token)), caCertificate)
                .also { it.trustedServer(clock) }
        } catch (error: ConnectionException) { throw error } catch (_: Exception) { throw ConnectionException("registration_lost") }

        internal fun parse(raw: String, clock: () -> Long = System::currentTimeMillis): DeviceCredentials = try {
            val fields = ProtocolCodec.readObject(raw, 4096)
            if (fields["recordVersion"] != JsonPrimitive(2) || fields["transport"] != JsonPrimitive(TrustedServer.TRANSPORT) || !fields.containsKey("caCertificate")) {
                throw ConnectionException("tls_repair_required")
            }
            create(ProtocolCodec.text(fields["serverId"]), ProtocolCodec.text(fields["deviceId"]),
                ProtocolCodec.integer(fields["generation"], 1), ProtocolCodec.text(fields["token"]), ProtocolCodec.text(fields["caCertificate"]), clock)
        } catch (error: ConnectionException) { throw error } catch (_: Exception) { throw ConnectionException("registration_lost") }
    }
}

interface RegistrationStorage {
    fun load(): DeviceCredentials?
    fun save(credentials: DeviceCredentials)
    fun clear()
}

/** 토큰 문자열이 아니라 비추출 AES 키를 Android Keystore에 보관한다. */
class TokenStore(
    context: Context,
    directory: File = File(context.noBackupFilesDir, "companion"),
    private val alias: String = "ene_companion_token_v1",
    private val clock: () -> Long = System::currentTimeMillis,
) : RegistrationStorage {
    private val file = AtomicFile(File(directory, "registration.enc"))

    init { requireNoBackupDirectory(context, directory) }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw ConnectionException("registration_lost")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    override fun load(): DeviceCredentials? {
        return try {
            val raw = readAtomic(file) ?: return null
            val envelope = ProtocolCodec.readObject(raw.decodeToString(throwOnInvalidSequence = true), 8192)
            if (ProtocolCodec.integer(envelope["version"]) != 1L) throw ConnectionException("registration_lost")
            val iv = Base64.getDecoder().decode(ProtocolCodec.text(envelope["iv"]))
            val encrypted = Base64.getDecoder().decode(ProtocolCodec.text(envelope["ciphertext"]))
            if (iv.size != 12 || encrypted.size !in 17..4096) throw ConnectionException("registration_lost")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
            cipher.updateAAD("ene_companion_token_v1".toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(encrypted)
            try { DeviceCredentials.parse(plain.decodeToString(throwOnInvalidSequence = true), clock) } finally { plain.fill(0) }
        } catch (error: ConnectionException) { throw error } catch (_: Exception) { throw ConnectionException("registration_lost") }
    }

    override fun save(credentials: DeviceCredentials) {
        try {
            val raw = Json.encodeToString(credentials)
            DeviceCredentials.parse(raw, clock)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD("ene_companion_token_v1".toByteArray(Charsets.UTF_8))
            val plain = raw.toByteArray(Charsets.UTF_8)
            val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
            val envelope = buildJsonObject {
                put("version", 1)
                put("iv", Base64.getEncoder().encodeToString(cipher.iv))
                put("ciphertext", Base64.getEncoder().encodeToString(encrypted))
            }.toString().toByteArray(Charsets.UTF_8)
            writeAtomic(file, envelope)
            if (load() != credentials) throw ConnectionException("registration_save_failed")
        } catch (error: ConnectionException) {
            if (error.code in setOf("tls_expired", "tls_clock_invalid", "tls_identity_invalid", "tls_repair_required")) throw error
            throw ConnectionException("registration_save_failed")
        } catch (_: Exception) { throw ConnectionException("registration_save_failed") }
    }

    override fun clear() {
        file.delete()
        if (listOf("", ".bak", ".new").any { File(file.baseFile.path + it).exists() }) {
            throw ConnectionException("registration_save_failed")
        }
    }
}
