package dev.ene.companion.character

import android.content.Context
import dev.ene.companion.storage.DeviceCredentials
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.util.Base64

/** 각 받기 작업이 전송 객체 하나를 소유한다. 취소/모델 교체 때 다른 음성 HTTP는 건드리지 않는다. */
interface CharacterMedia : Closeable {
    suspend fun manifest(): ByteArray
    suspend fun asset(asset: CharacterAsset, onChunk: (ByteArray, Int) -> Unit)
}

class CharacterLoad(val snapshot: CharacterSnapshot, val character: CharacterCache.CachedCharacter?) : Closeable {
    override fun close() { character?.close() }
}

/** 최신 연결·모델 세대의 manifest 없이는 이전 캐시를 화면에 돌려주지 않는다. */
class CharacterRepository(private val cache: CharacterCache) {
    companion object {
        /** Application이 하나만 생성해 공유한다. 인증서/서버 ID도 디스크에는 해시 이름으로만 남긴다. */
        suspend fun create(context: Context): CharacterRepository = withContext(Dispatchers.IO) {
            CharacterRepository(CharacterCache(File(context.applicationContext.noBackupFilesDir, "character")))
        }

        fun identity(credentials: DeviceCredentials): String {
            val caHash = CharacterSnapshot.hash(Base64.getDecoder().decode(credentials.caCertificate))
            return CharacterSnapshot.hash("${credentials.serverId}\u0000$caHash\u0000${credentials.deviceId}\u0000${credentials.generation}".toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun load(identity: String, media: CharacterMedia, isCurrent: () -> Boolean): CharacterLoad {
        // IO → 호출 문맥 복귀 직전 취소되는 경우에도 새 캐시 핀이 유실되지 않게 바깥에서 회수한다.
        var result: CharacterLoad? = null
        try {
            return withContext(Dispatchers.IO) {
                withTimeout(300_000) {
                    coroutineScope {
                        suspend fun checkCurrent() {
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent()) throw CharacterException("stale_character")
                        }
                        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                while (isActive) { checkCurrent(); delay(100) }
                            } finally { media.close() }
                        }
                        try {
                            checkCurrent()
                            val snapshot = CharacterSnapshot.parse(media.manifest())
                            checkCurrent()
                            val character = if (snapshot.status != "ready") null else {
                                cache.acquire(identity, snapshot) ?: cache.begin(identity, snapshot).use { ticket ->
                                    for (asset in snapshot.assets) {
                                        // 서버의 초당 요청 한도를 음성/manifest와 함께 넘지 않도록 여유를 둔다.
                                        delay(150)
                                        checkCurrent()
                                        ticket.open(asset.id).use { writer ->
                                            media.asset(asset) { bytes, count ->
                                                if (!isCurrent()) throw CharacterException("stale_character")
                                                require(count in 1..minOf(bytes.size, 64 * 1024)) { "invalid_chunk" }
                                                writer.write(bytes, 0, count)
                                            }
                                        }
                                    }
                                    checkCurrent()
                                    ticket.commit()
                                }
                            }
                            CharacterLoad(snapshot, character).also { result = it; checkCurrent() }
                        } finally { media.close(); watcher.cancel() }
                    }
                }
            }
        } catch (error: Throwable) { result?.close(); throw error }
        finally { media.close() }
    }
}
