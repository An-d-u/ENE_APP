package dev.ene.companion.ui

import dev.ene.companion.connection.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun ConnectionDiagnosticsDialog(diagnostics: ConnectionDiagnostics, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("연결 진단") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("현재 단계: ${connectionStageDescription(diagnostics.stage)}")
            diagnostics.lastFailure?.let {
                Text("마지막 실패 단계: ${connectionStageDescription(it.stage)}\n사유 코드: ${diagnosticCode(it.code)}\n${errorDescription(it.code)}")
            } ?: Text("이번 실행에서 기록된 연결 실패가 없습니다.")
            diagnostics.discoveryNotice?.let { Text(discoveryDescription(it)) }
            diagnostics.storageNotice?.let { Text(storageNoticeDescription()) }
            Text("직전 앱 종료: ${previousExitDescription(diagnostics.previousExit)}")
            if (diagnostics.previousExit.status == ExitRecordStatus.AVAILABLE) diagnostics.previousExit.timestampMillis?.let {
                Text("종료 시각: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}")
            }
            Text("종료 사유는 Android의 기록이며, 연결 후 앱이 닫힌 원인을 확정하는 정보는 아닙니다. 기록이 없을 수도 있습니다.", style = MaterialTheme.typography.bodySmall)
            Text("대화·토큰·인증서·PC 주소는 이 진단에 포함하거나 별도 파일로 저장하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } })
}

internal fun connectionStageDescription(stage: ConnectionStage): String = when (stage) {
    ConnectionStage.IDLE -> "연결 대기"
    ConnectionStage.SAVED_ADDRESSES -> "저장된 PC 주소를 확인하고 있습니다"
    ConnectionStage.DISCOVERY -> "PC 주소를 찾고 있습니다"
    ConnectionStage.SERVER_INFO -> "등록된 PC인지 확인하고 있습니다"
    ConnectionStage.SECURE_SESSION -> "암호화 연결을 준비하고 있습니다"
    ConnectionStage.INITIAL_SYNC -> "전체 대화를 동기화하고 있습니다"
    ConnectionStage.CONNECTED -> "연결 완료"
    ConnectionStage.RETRY_WAIT -> "잠시 후 다시 연결합니다"
}

internal fun discoveryDescription(code: String): String = when (diagnosticCode(code)) {
    "discovery_no_wifi", "discovery_network_unavailable" -> "자동 탐색은 같은 Wi-Fi에서 사용할 수 있습니다. PC 주소를 알고 있다면 ‘주소 수정’으로 연결할 수 있습니다."
    "discovery_network_changed" -> "탐색 중 네트워크가 바뀌었습니다. 새 Wi-Fi 연결을 확인한 뒤 다시 시도해 주세요."
    "discovery_cooldown", "discovery_busy" -> "자동 탐색은 잠시 후 다시 시도합니다. 수동 주소 연결은 계속 사용할 수 있습니다."
    "discovery_stop_failed", "discovery_resolver_busy" -> "Android의 이전 탐색 요청이 아직 정리되지 않았습니다. ‘주소 수정’으로 연결하거나 앱을 다시 열어 주세요."
    else -> "PC 주소를 자동으로 찾지 못했습니다. PC 실행 상태·공유기의 기기 간 통신 제한을 확인하거나 ‘주소 수정’을 사용해 주세요."
}

internal fun storageNoticeDescription(): String = "현재 연결은 유지되지만 PC 주소를 저장하지 못했습니다. 다음 연결에서 자동 탐색이나 주소 수정이 다시 필요할 수 있습니다."

internal fun previousExitDescription(exit: PreviousExit): String = when (exit.status) {
    ExitRecordStatus.LOADING -> "기록을 확인하고 있습니다"
    ExitRecordStatus.UNSUPPORTED -> "이 Android 버전에서는 조회를 지원하지 않습니다"
    ExitRecordStatus.EMPTY -> "이전 앱 종료 기록이 없습니다"
    ExitRecordStatus.UNAVAILABLE -> "종료 기록을 조회하지 못했습니다"
    ExitRecordStatus.AVAILABLE -> when (exit.reason) {
        1 -> "앱이 종료를 요청했습니다"
        2 -> "운영체제 신호로 종료되었습니다"
        3 -> "메모리 부족으로 종료되었습니다"
        4 -> "앱 예외로 종료되었습니다"
        5 -> "네이티브 코드 오류로 종료되었습니다"
        6 -> "앱 응답 지연으로 종료되었습니다"
        10, 11 -> "사용자 또는 시스템의 앱 중지 요청으로 종료되었습니다"
        else -> "알 수 없는 종료 사유입니다"
    }
}
