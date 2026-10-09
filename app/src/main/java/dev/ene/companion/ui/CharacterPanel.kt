package dev.ene.companion.ui

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ene.companion.character.CharacterViewState
import dev.ene.companion.character.CharacterWebView
import dev.ene.companion.connection.ConnectionRepository

/** 대화창과 독립된 배경 계층. 키보드와 전경 높이는 렌더러 수명에 관여하지 않는다. */
@Composable
fun CharacterScene(repository: ConnectionRepository, visible: Boolean = true) {
    val state by repository.characterState.collectAsStateWithLifecycle()
    val placement by repository.characterPlacement.collectAsStateWithLifecycle()
    SideEffect { repository.characterPanelVisible(visible) }
    DisposableEffect(repository) { onDispose { repository.characterPanelVisible(false); repository.finishCharacterPlacement() } }
    CharacterSurface(state, placement.loaded, visible,
            factory = { context ->
                var mounted: CharacterWebView? = null
                var startupFailure: String? = null
                val view = CharacterWebView(context,
                    onEvent = { event -> mounted?.let { repository.characterEvent(it, event) } },
                    onFailure = { code ->
                        val current = mounted
                        if (current == null) startupFailure = code else repository.characterFailed(current, code)
                    })
                mounted = view
                repository.attachCharacter(view)
                // 생성 중 실패도 새 renderer 등록 후 전달한다. 원시 오류나 URL은 표시하지 않는다.
                startupFailure?.let { code -> Handler(Looper.getMainLooper()).post { repository.characterFailed(view, code) } }
                view
            },
            onRelease = { view -> repository.detachCharacter(view); view.close() },
        )
}

/** 표시 영역의 접힘과 뷰 소유권을 분리한다. 세대 교체와 실제 종료에서만 해제한다. */
@Composable
internal fun <T : View> CharacterSurface(state: CharacterViewState, placementLoaded: Boolean, shown: Boolean,
                                         factory: (Context) -> T, onRelease: (T) -> Unit) {
    if (state.retainRenderer && placementLoaded) key(state.viewGeneration) {
        val visible = state.presentationAllowed && shown
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(factory = factory, onReset = null, onRelease = onRelease,
                modifier = Modifier.fillMaxSize().semantics { if (visible) contentDescription = "ENE 캐릭터" },
                update = { view ->
                    if (view is CharacterWebView) view.composeVisible(visible)
                    else {
                        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                        view.isEnabled = visible
                        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                })
        }
    }
}

@Composable
fun CharacterStatus(state: CharacterViewState, onRetry: () -> Unit) {
    val text = when {
        state.status == "loading" -> "PC의 캐릭터를 가져오고 있습니다."
        state.status == "rendering" -> "캐릭터를 화면에 준비하고 있습니다."
        state.status == "refreshing" -> "캐릭터 상태를 갱신하고 있습니다."
        state.status == "error" -> "캐릭터를 표시하지 못했습니다. 채팅과 음성은 계속 사용할 수 있습니다."
        state.status == "unsupported" -> "현재 연결에서는 캐릭터 표시를 지원하지 않습니다."
        state.status == "unavailable" -> "PC에서 표시할 캐릭터를 선택해 주세요."
        else -> null
    }
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.status == "error") {
        Text(characterFailureDescription(state.errorCode), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("캐릭터 다시 불러오기") }
    }
}
