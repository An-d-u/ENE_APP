package dev.ene.companion.storage

import android.content.Context
import android.util.AtomicFile
import dev.ene.companion.connection.ConnectionException
import dev.ene.companion.connection.Endpoint
import dev.ene.companion.protocol.ProtocolCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Collections

@Serializable
data class ConnectionProfile(val serverId: String, val addresses: List<Endpoint>)

interface ConnectionSettingsStorage {
    fun load(): ConnectionProfile?
    fun save(profile: ConnectionProfile)
    fun clear()
}

class ConnectionSettingsStore(context: Context, directory: File = File(context.noBackupFilesDir, "companion")) : ConnectionSettingsStorage {
    private val file = AtomicFile(File(directory, "connection.json"))

    init { requireNoBackupDirectory(context, directory) }

    override fun load(): ConnectionProfile? {
        return try {
            val raw = readAtomic(file) ?: return null
            val fields = ProtocolCodec.readObject(raw.decodeToString(throwOnInvalidSequence = true), 8192)
            val serverId = ProtocolCodec.uuid(fields["serverId"])
            val addresses = fields["addresses"] as? JsonArray ?: throw ConnectionException("invalid_connection_settings")
            if (addresses.size !in 1..8) throw ConnectionException("invalid_connection_settings")
            val endpoints = addresses.map {
                val value = it as? JsonObject ?: throw ConnectionException("invalid_connection_settings")
                Endpoint.parse(ProtocolCodec.text(value["host"]), ProtocolCodec.integer(value["port"], 1, 65535).toInt())
            }.distinct()
            ConnectionProfile(serverId, Collections.unmodifiableList(endpoints))
        } catch (_: Exception) { throw ConnectionException("invalid_connection_settings") }
    }

    override fun save(profile: ConnectionProfile) {
        try {
            ProtocolCodec.uuid(JsonPrimitive(profile.serverId))
            if (profile.addresses.size !in 1..8) throw ConnectionException("invalid_connection_settings")
            val normalized = ConnectionProfile(profile.serverId, profile.addresses.map { Endpoint.parse(it.host, it.port) }.distinct())
            writeAtomic(file, Json.encodeToString(normalized).toByteArray(Charsets.UTF_8))
            if (load() != normalized) throw ConnectionException("connection_settings_save_failed")
        } catch (_: Exception) { throw ConnectionException("connection_settings_save_failed") }
    }

    override fun clear() {
        file.delete()
        if (listOf("", ".bak", ".new").any { File(file.baseFile.path + it).exists() }) {
            throw ConnectionException("connection_settings_save_failed")
        }
    }
}

internal fun requireNoBackupDirectory(context: Context, directory: File) {
    val root = context.noBackupFilesDir.canonicalFile
    val target = directory.canonicalFile
    require(target == root || target.path.startsWith(root.path + File.separator)) { "no_backup_directory_required" }
}

internal fun readAtomic(file: AtomicFile): ByteArray? {
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
    file.openRead().use { input ->
        val bytes = ByteArray(8193)
        var count = 0
        while (count < bytes.size) {
            val next = input.read(bytes, count, bytes.size - count)
            if (next < 0) break
            count += next
        }
        if (count > 8192) throw ConnectionException("storage_too_large")
        return bytes.copyOf(count)
    }
}

internal fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
    file.baseFile.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw ConnectionException("storage_unavailable") }
    val output = file.startWrite()
    try {
        output.write(bytes)
        output.fd.sync()
        file.finishWrite(output)
    } catch (error: Exception) {
        file.failWrite(output)
        throw error
    }
}
