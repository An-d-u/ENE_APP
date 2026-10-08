package dev.ene.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ene.companion.R
import dev.ene.companion.connection.ChatActionsViewState

/** 시간과 조작은 원본 묶음당 한 번만 그린다. */
@Composable
internal fun ChatMessageMeta(id: String, role: String, time: String?, actions: ChatActionsViewState,
                             hasThought: Boolean, expanded: Boolean, onToggleThought: () -> Unit,
                             onEdit: (String) -> Unit, onReroll: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = if (role == "user") Alignment.End else Alignment.Start) {
        time?.let { Text(it, Modifier.testTag("message-time"), style = MaterialTheme.typography.labelSmall) }
        Row {
            if (hasThought) IconButton(onClick = onToggleThought,
                modifier = Modifier.size(48.dp).semantics { stateDescription = if (expanded) "생각 펼쳐짐" else "생각 접힘" }) {
                Icon(painterResource(R.drawable.ic_chat_thought), if (expanded) "생각 접기" else "생각 보기", Modifier.size(18.dp))
            }
            ChatMessageActions(id, role, actions, onEdit, onReroll)
        }
    }
}

@Composable
fun ChatMessageActions(id: String, role: String, state: ChatActionsViewState,
                            onEdit: (String) -> Unit, onReroll: (String) -> Unit) {
    val kind = messageAction(id, role, state) ?: return
    IconButton(onClick = { if (kind == "edit") onEdit(id) else onReroll(id) },
        enabled = if (kind == "edit") state.canEdit else state.canReroll,
        colors = IconButtonDefaults.iconButtonColors(contentColor = LocalContentColor.current,
            disabledContentColor = LocalContentColor.current.copy(alpha = .6f)), modifier = Modifier.size(48.dp)) {
        Icon(painterResource(if (kind == "edit") R.drawable.ic_chat_edit else R.drawable.ic_chat_reroll),
            if (kind == "edit") "수정" else "리롤", Modifier.size(18.dp))
    }
}

@Composable
internal fun ChatActionNotice(id: String, role: String, state: ChatActionsViewState) {
    val kind = messageAction(id, role, state) ?: return
    val enabled = if (kind == "edit") state.canEdit else state.canReroll
    if (!enabled) Text(chatActionReason(if (kind == "edit") state.editReason else state.rerollReason),
        style = MaterialTheme.typography.bodySmall)
}
