package dev.ene.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ene.companion.character.CharacterPlacement
import dev.ene.companion.storage.CharacterPlacementStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 실제 원자 파일 동작은 계측 환경에서만 검사한다. 로컬 빌드와 실행 결과를 구분한다. */
class CharacterPlacementStoreTest {
    private fun inDirectory(block: suspend (Context, File) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir, "placement-test-" + UUID.randomUUID())
        check(directory.mkdir())
        try { block(context, directory) } finally {
            check(directory.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
            check(directory.name.startsWith("placement-test-"))
            directory.deleteRecursively()
        }
    }
    @Test fun roundTripAndFailedWritePreserveLastCompleteFile() = inDirectory { context, directory ->
        val store = CharacterPlacementStore(context, directory)
        assertNull(store.load())
        val value = CharacterPlacement(6.0, -300.0, 400.0)
        store.save(value)
        assertEquals(value, CharacterPlacementStore(context, directory).load())
        val pending = File(directory, "placement.json.new")
        check(pending.mkdir()); File(pending, "synthetic").writeText("합성 방해 파일")
        try { store.save(CharacterPlacement(6.0, 400.0, -300.0)); fail("쓰기 실패가 필요합니다") }
        catch (_: IllegalArgumentException) { }
        assertEquals(value, store.load())
    }
    @Test fun malformedFileIsNotReplacedOnRead() = inDirectory { context, directory ->
        val file = File(directory, "placement.json").apply { writeText("합성 손상 자료") }
        val before = file.readBytes()
        try { CharacterPlacementStore(context, directory).load(); fail("읽기 거절이 필요합니다") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, file.readBytes())
        assertThrows(IllegalArgumentException::class.java) { CharacterPlacementStore(context, context.filesDir) }
    }
}
