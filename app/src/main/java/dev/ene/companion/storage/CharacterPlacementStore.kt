package dev.ene.companion.storage

import android.content.Context
import android.util.AtomicFile
import dev.ene.companion.character.*
import kotlinx.coroutines.CancellationException
import java.io.File

/** 등록과 모델 캐시의 수명 밖에 두는 휴대폰 공통 배치. 인증 정보는 저장하지 않는다. */
class CharacterPlacementStore(context: Context, directory: File = File(context.noBackupFilesDir, "character_presentation")) : CharacterPlacementStorage {
    private val file = AtomicFile(File(directory, "placement.json"))
    init { requireNoBackupDirectory(context, directory) }
    override suspend fun load(): CharacterPlacement? = try {
        readAtomic(file)?.let(CharacterPlacementCodec::decode)
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { throw IllegalArgumentException("character_placement_read_failed") }

    override suspend fun save(value: CharacterPlacement) {
        try {
            writeAtomic(file, CharacterPlacementCodec.encode(value))
            check(load() == value)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw IllegalArgumentException("character_placement_save_failed") }
    }
}
