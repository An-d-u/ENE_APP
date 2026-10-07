package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ene.companion.connection.ThoughtContent

/** 펼침 상태와 내용은 화면 메모리에만 유지한다. */
@Composable
fun MessageThought(content: ThoughtContent?, onRetry: () -> Unit) {
    var expanded by remember(content) { mutableStateOf(false) }
    when (content?.status) {
        "available" -> Column {
            TextButton(onClick = { expanded = !expanded },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                    stateDescription = if (expanded) "생각 펼쳐짐" else "생각 접힘"
                }) { Text(if (expanded) "접기" else "생각 보기") }
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
