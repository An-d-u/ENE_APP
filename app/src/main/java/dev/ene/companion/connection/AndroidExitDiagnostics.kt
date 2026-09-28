package dev.ene.companion.connection

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException

/** 종료 기록의 설명·추적 파일을 읽지 않고 자체 메인 프로세스의 사유와 시각만 고른다. */
internal fun androidPreviousExit(context: Context, startedMillis: Long): PreviousExit {
    if (Build.VERSION.SDK_INT < 30) return PreviousExit(ExitRecordStatus.UNSUPPORTED)
    return try {
        val manager = context.getSystemService(ActivityManager::class.java)
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0).map {
            ExitSample(it.processName, it.reason, it.timestamp)
        }
        selectPreviousExit(records, context.applicationInfo.processName, startedMillis)
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { PreviousExit(ExitRecordStatus.UNAVAILABLE) }
}
