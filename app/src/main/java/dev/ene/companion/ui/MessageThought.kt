package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ThoughtContent

/** 펼침 상태는 원본 메시지 소유자가 보관하며 본문·오류만 그린다. */
@Composable
fun MessageThought(content: ThoughtContent?, expanded: Boolean, onRetry: () -> Unit) {
    when (content?.status) {
        "available" -> Column {
            if (expanded) {
                HorizontalDivider()
                Text(content.text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        "too_large" -> Text("생각 내용이 길어 PC에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
        "error" -> TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
            Text("생각을 가져오지 못했습니다 · 다시 시도")
        }
    }
}
