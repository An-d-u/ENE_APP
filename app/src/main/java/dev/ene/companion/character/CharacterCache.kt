package dev.ene.companion.character

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** 앱 전용 noBackupFilesDir에서만 사용한다. 모든 디스크 작업은 IO 실행 문맥에서 호출한다. */
class CharacterCache(
    root: File,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val freeBytes: () -> Long = { root.usableSpace },
) {
    private val root = root.toPath().toAbsolutePath().normalize()
    private val entries = linkedMapOf<String, Entry>()
    private val tickets = mutableSetOf<Ticket>()
    private var clock = 0L
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val stagingPattern = Regex("[a-f0-9]{64}\\.part-[a-f0-9-]{36}")

    internal class Entry(val path: Path, val snapshot: CharacterSnapshot, val bytes: Long, var touched: Long) {
        var pins = 0
    }

    val versionCount: Int @Synchronized get() = entries.size + tickets.size
    val usedBytes: Long @Synchronized get() = entries.values.sumOf { it.bytes } + tickets.sumOf { it.reserved }

    @Synchronized fun clear() {
        require(tickets.isEmpty() && entries.values.none { it.pins > 0 }) { "cache_busy" }
        safeDirectory(root)
        entries.toList().forEach { (key, entry) ->
            removeOwned(entry.path)
            entries.remove(key)
        }
        children(root).filter { digestPattern.matches(it.fileName.toString()) && directory(it) }.forEach { bucket ->
            // 중단된 다운로드도 정리하되, 캐시 소유 이름이 아닌 파일과 링크는 건드리지 않는다.
            children(bucket).filter { digestPattern.matches(it.fileName.toString()) || stagingPattern.matches(it.fileName.toString()) }
                .forEach(::removeOwned)
            if (children(bucket).isEmpty()) Files.delete(bucket)
        }
    }

    init {
        require(maxBytes > 0) { "invalid_cache_budget" }
        Files.createDirectories(this.root)
        safeDirectory(this.root)
        // 이름을 검증한 전용 하위 폴더만 회수한다. 알 수 없는 파일이나 외부 링크는 따라가지 않는다.
        children(this.root).filter { digestPattern.matches(it.fileName.toString()) && directory(it) }.forEach { bucket ->
            children(bucket).forEach { version ->
                val name = version.fileName.toString()
                if (stagingPattern.matches(name)) removeOwned(version)
                else if (digestPattern.matches(name) && directory(version)) {
                    val snapshot = runCatching { readManifest(version) }.getOrNull()
                    if (snapshot == null || snapshot.modelVersion != name || snapshot.json != staticDescriptor(snapshot)) removeOwned(version)
                    else {
                        val entry = Entry(version, snapshot, reservation(snapshot), ++clock)
                        if (verified(entry)) entries[key(bucket.fileName.toString(), name)] = entry else removeOwned(version)
                    }
                }
            }
        }
        trim(0, 0)
    }

    fun acquire(identity: String, snapshot: CharacterSnapshot): CachedCharacter? {
        val entry = synchronized(this) {
            validate(identity, snapshot)
            (entries[key(identity, snapshot.modelVersion!!)] ?: return null).also { it.pins++ }
        }
        // 대용량 해시를 읽는 동안 Main의 mount 해제/보존을 잠그지 않는다.
        val valid = verified(entry)
        return synchronized(this) {
            if (!valid) {
                entry.pins--
                if (entry.pins == 0) { entries.remove(key(identity, snapshot.modelVersion!!)); removeOwned(entry.path) }
                null
            } else {
                entry.touched = ++clock
                CachedCharacter(entry, snapshot)
            }
        }
    }

    @Synchronized fun begin(identity: String, snapshot: CharacterSnapshot): Ticket {
        validate(identity, snapshot)
        val version = snapshot.modelVersion!!
        require(entries[key(identity, version)] == null && tickets.none { it.identity == identity && it.snapshot.modelVersion == version }) { "version_exists" }
        val reserved = reservation(snapshot)
        trim(reserved, 1, checkStorage = true)
        require(freeBytes() >= reserved + 1024 * 1024) { "insufficient_storage" }
        val bucket = root.resolve(identity)
        Files.createDirectories(bucket)
        safeDirectory(bucket)
        val stage = bucket.resolve("$version.part-${UUID.randomUUID()}")
        Files.createDirectory(stage)
        return Ticket(identity, snapshot, stage, reserved).also { tickets.add(it) }
    }

    private fun validate(identity: String, snapshot: CharacterSnapshot) {
        require(digestPattern.matches(identity) && snapshot.status == "ready" && snapshot.modelVersion != null) { "invalid_cache_identity" }
        safeDirectory(root)
    }
    private fun key(identity: String, version: String) = "$identity/$version"
    private fun staticDescriptor(snapshot: CharacterSnapshot): JsonObject {
        val expressions = snapshot.json.getValue("expression_ids").jsonArray
        val base = expressions.firstOrNull { it.jsonPrimitive.content == "normal" } ?: expressions.first()
        // 재접속 시 최신 상태를 서버에서 받는다. 디스크에는 모델 검증용 정보만 남긴다.
        return JsonObject(snapshot.json.toMutableMap().apply {
            put("settings", JsonObject(emptyMap())); put("parameters", JsonObject(emptyMap()))
            put("state_revision", JsonPrimitive(1)); put("settings_revision", JsonPrimitive(1)); put("action_seq", JsonPrimitive(0))
            put("default_expression", base)
            put("head_pat_defaults", buildJsonObject { put("active", base); put("end", base) })
        })
    }
    private fun reservation(snapshot: CharacterSnapshot) = snapshot.totalBytes + snapshot.json.toString().toByteArray(Charsets.UTF_8).size
    private fun directory(path: Path) = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    private fun safeDirectory(path: Path) { require(directory(path)) { "unsafe_cache_path" } }
    private fun children(path: Path): List<Path> = Files.newDirectoryStream(path).use { it.toList() }

    private fun trim(addBytes: Long, addVersions: Int, checkStorage: Boolean = false) {
        require(addBytes <= maxBytes) { "cache_budget" }
        while (usedBytes + addBytes > maxBytes || versionCount + addVersions > 2 ||
            (checkStorage && freeBytes() < addBytes + 1024 * 1024)) {
            val victim = entries.entries.filter { it.value.pins == 0 }.minByOrNull { it.value.touched }
                ?: throw CharacterException("cache_busy")
            removeOwned(victim.value.path)
            entries.remove(victim.key)
        }
    }

    private fun removeOwned(path: Path) {
        val parent = path.parent ?: throw CharacterException("unsafe_cache_path")
        require(parent.parent == root && digestPattern.matches(parent.fileName.toString()) &&
            (digestPattern.matches(path.fileName.toString()) || stagingPattern.matches(path.fileName.toString()))) { "unsafe_cache_path" }
        safeDirectory(root)
        safeDirectory(parent)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun openFile(path: Path): InputStream {
        safeDirectory(root); safeDirectory(path.parent.parent); safeDirectory(path.parent)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "unsafe_cache_path" }
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }

    private fun readManifest(path: Path): CharacterSnapshot {
        val file = path.resolve("manifest.json")
        require(Files.size(file) in 1..CharacterSnapshot.MAX_MANIFEST_BYTES.toLong()) { "invalid_manifest" }
        val buffer = java.io.ByteArrayOutputStream()
        openFile(file).use { source ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                require(buffer.size() + count <= CharacterSnapshot.MAX_MANIFEST_BYTES) { "invalid_manifest" }
                buffer.write(chunk, 0, count)
            }
        }
        return CharacterSnapshot.parse(buffer.toByteArray())
    }

    private fun verified(entry: Entry): Boolean = runCatching {
        val expected = entry.snapshot.assets.map { it.id }.toSet() + "manifest.json"
        require(children(entry.path).map { it.fileName.toString() }.toSet() == expected)
        val buffer = ByteArray(64 * 1024)
        entry.snapshot.assets.forEach { asset ->
            val path = entry.path.resolve(asset.id)
            require(Files.size(path) == asset.size)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            openFile(path).use { source ->
                while (true) {
                    val size = source.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= asset.size)
                    digest.update(buffer, 0, size)
                }
            }
            require(count == asset.size && CharacterSnapshot.hex(digest.digest()) == asset.sha256)
            if (asset.mime.startsWith("image/")) openFile(path).use { CharacterImage.verify(it, asset.mime) }
        }
        true
    }.getOrDefault(false)

    inner class CachedCharacter internal constructor(private val entry: Entry, val snapshot: CharacterSnapshot) : Closeable {
        private var closed = false
        fun retain(): CachedCharacter = synchronized(this@CharacterCache) {
            check(!closed) { "closed_character" }
            entry.pins++
            CachedCharacter(entry, snapshot)
        }
        fun open(assetId: String): InputStream = synchronized(this@CharacterCache) {
            check(!closed) { "closed_character" }
            require(snapshot.assets.any { it.id == assetId }) { "unknown_asset" }
            val source = openFile(entry.path.resolve(assetId))
            entry.pins++
            object : FilterInputStream(source) {
                private var done = false
                override fun close() = synchronized(this@CharacterCache) {
                    if (!done) { done = true; try { super.close() } finally { entry.pins-- } }
                }
            }
        }
        override fun close() = synchronized(this@CharacterCache) {
            if (!closed) { closed = true; entry.pins-- }
        }
    }

    inner class Ticket internal constructor(
        internal val identity: String,
        internal val snapshot: CharacterSnapshot,
        private val stage: Path,
        internal val reserved: Long,
    ) : Closeable {
        private var closed = false
        private val complete = mutableSetOf<String>()
        private val writers = mutableMapOf<String, Writer>()

        fun open(assetId: String): OutputStream = synchronized(this@CharacterCache) {
            check(!closed) { "closed_download" }
            val asset = snapshot.assets.find { it.id == assetId } ?: throw CharacterException("unknown_asset")
            require(assetId !in complete && assetId !in writers) { "duplicate_asset" }
            safeDirectory(stage.parent); safeDirectory(stage)
            Writer(asset).also { writers[assetId] = it }
        }

        fun commit(): CachedCharacter = synchronized(this@CharacterCache) {
            check(!closed && writers.isEmpty() && complete.size == snapshot.assets.size) { "incomplete_download" }
            val manifest = staticDescriptor(snapshot).toString().toByteArray(Charsets.UTF_8)
            FileChannel.open(stage.resolve("manifest.json"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                val buffer = ByteBuffer.wrap(manifest)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            val target = stage.parent.resolve(snapshot.modelVersion!!)
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "version_exists" }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
            closed = true
            tickets.remove(this)
            val entry = Entry(target, snapshot, reserved, ++clock).also { it.pins = 1 }
            entries[key(identity, snapshot.modelVersion)] = entry
            CachedCharacter(entry, snapshot)
        }

        override fun close() = synchronized(this@CharacterCache) {
            if (!closed) {
                closed = true
                writers.values.toList().forEach { it.cancel() }
                try { removeOwned(stage) } finally { tickets.remove(this) }
            }
        }

        private inner class Writer(private val asset: CharacterAsset) : OutputStream() {
            private val path = stage.resolve("${asset.id}.part")
            private val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            private val digest = MessageDigest.getInstance("SHA-256")
            private var count = 0L
            private var done = false
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(this@CharacterCache) {
                check(!closed && !done) { "closed_download" }
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "invalid_chunk" }
                if (count + length > asset.size) { cancel(); throw CharacterException("asset_size") }
                try {
                    val buffer = ByteBuffer.wrap(bytes, offset, length)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    digest.update(bytes, offset, length)
                    count += length
                } catch (error: Exception) { cancel(); throw error }
            }
            fun cancel() {
                if (!done) {
                    done = true
                    try { channel.close() } finally { writers.remove(asset.id); Files.deleteIfExists(path) }
                }
            }
            override fun close() = synchronized(this@CharacterCache) {
                if (done) return@synchronized
                if (count != asset.size || CharacterSnapshot.hex(digest.digest()) != asset.sha256) {
                    cancel(); throw CharacterException("asset_integrity")
                }
                try {
                    channel.force(true); channel.close()
                    if (asset.mime.startsWith("image/")) openFile(path).use { CharacterImage.verify(it, asset.mime) }
                    Files.move(path, stage.resolve(asset.id), StandardCopyOption.ATOMIC_MOVE)
                    complete.add(asset.id)
                    done = true
                    writers.remove(asset.id)
                } catch (error: Exception) { cancel(); throw error }
            }
        }
    }
}
