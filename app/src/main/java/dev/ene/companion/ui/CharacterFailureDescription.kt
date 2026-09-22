package dev.ene.companion.ui

/** 파일 경로나 임의 예외 원문 대신 고정된 실패 단계만 표시한다. */
internal fun characterFailureDescription(code: String?): String = when (code) {
    "character_download_failed" -> "PC에서 캐릭터 파일을 가져오지 못했습니다."
    "character_bridge_timeout" -> "캐릭터 화면의 초기화 응답을 받지 못했습니다."
    "character_initialization_failed" -> "캐릭터 표시 엔진을 초기화하지 못했습니다."
    "character_asset_failed" -> "캐릭터 파일을 화면에서 읽지 못했습니다."
    "character_renderer_gone" -> "캐릭터 표시 엔진이 종료되었습니다."
    "character_webview_unsupported" -> "휴대폰 WebView가 필요한 내부 통신 기능을 지원하지 않습니다."
    "character_navigation_blocked" -> "허용되지 않은 캐릭터 화면 이동을 차단했습니다."
    "character_state_stale" -> "PC와 캐릭터 상태가 일치하지 않습니다. 다시 불러와 주세요."
    else -> "캐릭터 모델을 화면에 표시하지 못했습니다."
}
