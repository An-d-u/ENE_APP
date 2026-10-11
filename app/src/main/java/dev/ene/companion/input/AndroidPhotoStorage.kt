package dev.ene.companion.input

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 사용자 URI는 읽기만 하고 앱 소유 임시 파일은 무작위 이름으로만 저장한다. */
class AndroidPhotoStorage(context: Context) : PhotoStorage {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.noBackupFilesDir, "photo-drafts")
    private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private fun target(id: String): File {
        require(idPattern.matches(id)) { "photo_storage_failed" }
        return File(directory, "$id.image").also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "photo_storage_failed" }
        }
    }
    override suspend fun initialize() = withContext(Dispatchers.IO) {
        require(directory.isDirectory || directory.mkdirs()) { "photo_storage_failed" }
        for (file in directory.listFiles().orEmpty()) {
            val id = file.name.removeSuffix(".image")
            if (file.name == "$id.image" && idPattern.matches(id) && file == target(id))
                require(file.delete() || !file.exists()) { "photo_storage_failed" }
        }
    }
    override suspend fun prepare(source: String): LocalPhoto = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        require(uri.scheme == "content") { "unsupported_image" }
        val image = requireNotNull(resolver.openInputStream(uri)).use(ImagePreparation::prepare)
        val preview = ImagePreparation.preview(image)
        val id = UUID.randomUUID().toString()
        val file = target(id)
        try {
            file.outputStream().use { output -> output.write(image.bytes); output.fd.sync() }
            LocalPhoto(id, image.mime, image.bytes.sha256(), image.bytes.size.toLong(), image.width, image.height, preview)
        } catch (error: Exception) { file.delete(); throw error }
    }
    override suspend fun read(photo: LocalPhoto): ByteArray = withContext(Dispatchers.IO) {
        val file = target(photo.id)
        require(photo.byteLength in 1..ImagePolicy.IMAGE_BYTES.toLong() && file.length() == photo.byteLength) { "photo_unavailable" }
        val bytes = file.inputStream().use { input ->
            val result = ByteArray(photo.byteLength.toInt())
            var offset = 0
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                require(count > 0) { "photo_unavailable" }; offset += count
            }
            require(input.read() == -1) { "photo_unavailable" }; result
        }
        require(bytes.sha256() == photo.sha256) { "photo_unavailable" }
        bytes
    }
    override suspend fun delete(photos: List<LocalPhoto>) = withContext(Dispatchers.IO) {
        for (photo in photos) target(photo.id).delete()
    }
}

internal fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
