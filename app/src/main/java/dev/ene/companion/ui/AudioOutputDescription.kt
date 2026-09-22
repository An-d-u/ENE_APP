package dev.ene.companion.ui

import dev.ene.companion.connection.AudioOutputStatus

/** 알 수 없는 원문은 화면에 그대로 노출하지 않는다. */
internal fun audioOutputDescription(status: AudioOutputStatus): String {
    val preference = when (status.preference) {
        "auto" -> "자동 선택"
        "pc" -> "PC 선택 · 다음 음성부터 적용"
        "phone" -> "휴대폰 선택 · PC 대체 안 함"
        else -> "출력 선택 정보 없음"
    }
    val state = when {
        status.state == "playing" && status.output == "phone" -> "휴대폰 재생 중"
        status.state == "playing" && status.output == "pc" -> "PC 재생 중"
        status.state == "preparing" -> "음성 준비 중"
        status.state == "stopped" -> "음성 중단"
        status.state == "idle" -> "음성 대기"
        else -> "상세 재생 상태 미제공"
    }
    val reason = when (status.reason) {
        "ready" -> "휴대폰 준비됨"
        "manual_pc" -> "PC 수동 선택"
        "tts_disabled", "output_disabled" -> "PC의 음성 사용 설정을 확인해 주세요"
        "phone_not_connected", "connection_closed" -> "휴대폰 연결이 끊겼습니다"
        "inactive" -> "앱을 화면에 열어 주세요"
        "syncing" -> "대화 동기화 중입니다"
        "audio_not_negotiated" -> "이 연결에서는 휴대폰 음성을 지원하지 않습니다"
        "phone_unavailable", "not_ready" -> "휴대폰이 아직 재생할 준비가 되지 않았습니다"
        "browser_tts" -> "브라우저 음성은 PC에서만 지원합니다"
        "unsupported_format", "invalid_pcm", "invalid_audio" -> "휴대폰에서 지원하지 않거나 잘못된 음성 형식입니다"
        "private_result" -> "비공개 파일 결과는 휴대폰 음성을 지원하지 않습니다"
        "prepare_timeout", "message_not_ready", "start_timeout" -> "휴대폰 준비 시간이 초과되었습니다"
        "focus_denied", "audio_focus_lost" -> "휴대폰 오디오 사용 권한을 얻지 못했거나 잃었습니다"
        "audio_route_changed" -> "휴대폰 오디오 장치가 바뀌어 음성을 중단했습니다"
        "audio_download_failed", "delivery_failed" -> "음성 전송에 실패했습니다"
        "audio_initialization_failed", "audio_start_failed", "audio_output_failed" -> "휴대폰 오디오 재생에 실패했습니다"
        "playback_timeout" -> "음성 재생 진행이 멈췄습니다"
        "buffer_full" -> "음성 버퍼가 가득 찼습니다"
        "frame_mismatch" -> "음성 길이 확인에 실패했습니다"
        "empty_audio" -> "생성된 음성이 없습니다"
        "tts_failed", "callback_failed" -> "PC 음성 생성 또는 처리에 실패했습니다"
        "interrupted", "noisy", "conversation_reset", "replaced", "stale_operation" -> "음성이 중단되었습니다"
        "audio_busy" -> "이전 음성을 정리 중입니다"
        "pc_fallback" -> "시작 전에 PC로 출력이 전환되었습니다"
        "generating" -> "PC에서 음성을 생성 중입니다"
        "preparing", "starting", "playing", "finished", null -> ""
        else -> "음성 상태를 확인해 주세요"
    }
    return listOf(preference, state, reason).filter { it.isNotBlank() }.joinToString(" · ")
}
