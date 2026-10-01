package dev.ene.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ene.companion.presentation.ChatLayout
import dev.ene.companion.storage.ChatLayoutStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ChatLayoutStoreTest {
    private fun inDirectory(block: suspend (Context, File) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "chat-layout-test-" + UUID.randomUUID())
        check(directory.mkdir())
        try { block(context, directory) } finally {
            check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            check(directory.name.startsWith("chat-layout-test-"))
            directory.deleteRecursively()
        }
    }
    @Test fun restartAndFailedWriteKeepTheLastCompleteLayout() = inDirectory { context, directory ->
        val store = ChatLayoutStore(context, directory)
        assertNull(store.load())
        store.save(ChatLayout(.62f))
        assertEquals(ChatLayout(.62f), ChatLayoutStore(context, directory).load())
        val pending = File(directory, "layout.json.new")
        check(pending.mkdir()); File(pending, "synthetic").writeText("합성 방해 파일")
        try { store.save(ChatLayout(.8f)); fail("쓰기 실패가 필요합니다") }
        catch (_: IllegalArgumentException) { }
        assertEquals(ChatLayout(.62f), store.load())
    }
    @Test fun corruptDataIsNotReplacedByReading() = inDirectory { context, directory ->
        val file = File(directory, "layout.json").apply { writeText("합성 손상 자료") }
        val before = file.readBytes()
        try { ChatLayoutStore(context, directory).load(); fail("읽기 거절이 필요합니다") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, file.readBytes())
        assertThrows(IllegalArgumentException::class.java) { ChatLayoutStore(context, context.filesDir) }
    }
}
