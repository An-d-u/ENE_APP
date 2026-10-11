package dev.ene.companion.input

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class AndroidBackgroundStorage(private val context: Context) : BackgroundStorage {
    private val directory = File(context.noBackupFilesDir, "phone-background")
    private val settingsFile get() = AtomicFile(File(directory, "settings.json"))
    private fun imageFile(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.image").also { check(it.canonicalFile.parentFile == directory.canonicalFile) }
    }
    override suspend fun load(): BackgroundSettings? = withContext(Dispatchers.IO) {
        if (!settingsFile.baseFile.exists() && !File(directory, "settings.json.bak").exists()) return@withContext null
        settingsFile.openRead().use { input ->
            val bytes = readBounded(input, 4096)
            require(bytes.size <= 4096)
            Json.decodeFromString<BackgroundSettings>(bytes.decodeToString(throwOnInvalidSequence = true)).checked()
        }
    }
    override suspend fun save(settings: BackgroundSettings) = withContext(Dispatchers.IO) {
        settings.checked()
        check(directory.isDirectory || directory.mkdirs())
        val file = settingsFile
        val output = file.startWrite()
        try {
            output.write(Json.encodeToString(BackgroundSettings.serializer(), settings).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }
    override suspend fun prepare(source: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        require(uri.scheme == "content")
        val normalized = context.contentResolver.openInputStream(uri).use { ImagePreparation.prepare(requireNotNull(it)) }
        check(directory.isDirectory || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        val file = imageFile(id)
        try { file.outputStream().use { it.write(normalized.bytes); it.fd.sync() }; id }
        catch (error: Exception) { file.delete(); throw error }
    }
    override suspend fun image(id: String): ByteArray = withContext(Dispatchers.IO) {
        val file = imageFile(id)
        require(file.length() in 1..4194304)
        val bytes = file.inputStream().use { readBounded(it, 4194304) }
        require(bytes.size <= 4194304)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        decoded.recycle()
        bytes
    }
    override suspend fun cleanup(keep: Set<String>) = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".image")
            if (file.name.endsWith(".image") && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && id !in keep) {
                check(file.delete() || !file.exists())
            }
        }
        Unit
    }
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
    }
}
