package dev.ene.companion.storage

import android.content.Context
import android.util.AtomicFile
import dev.ene.companion.presentation.*
import kotlinx.coroutines.CancellationException
import java.io.File

/** 캐릭터 배치·인증 정보와 분리해 높이 비율만 로컬에 저장한다. */
class ChatLayoutStore(context: Context, directory: File = File(context.noBackupFilesDir, "chat_presentation")) : ChatLayoutStorage {
    private val file = AtomicFile(File(directory, "layout.json"))
    init { requireNoBackupDirectory(context, directory) }
    override suspend fun load(): ChatLayout? = try {
        readAtomic(file)?.let(ChatLayoutCodec::decode)
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { throw IllegalArgumentException("chat_layout_read_failed") }

    override suspend fun save(value: ChatLayout) {
        try {
            writeAtomic(file, ChatLayoutCodec.encode(value))
            check(load() == value)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { throw IllegalArgumentException("chat_layout_save_failed") }
    }
}
