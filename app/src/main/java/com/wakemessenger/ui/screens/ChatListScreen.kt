package com.wakemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wakemessenger.ui.ChatListViewModel
import com.wakemessenger.xmpp.XmppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ChatListViewModel = viewModel()
) {
    val chats by vm.chats.collectAsState()
    val users by vm.users.collectAsState()
    val state by vm.connection.collectAsState()
    var showNewChat by remember { mutableStateOf(false) }
    var newJid by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Чаты")
                        Text(
                            connectionLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = connectionColor(state)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChat = true }) {
                Icon(Icons.Default.Add, contentDescription = "Новый чат")
            }
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Сообщений пока нет", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(chats, key = { it.chatJid }) { c ->
                    val presence = users.firstOrNull { it.jid == c.chatJid }?.presence ?: c.presence ?: "offline"
                    ChatRow(
                        title = c.nickname ?: c.chatJid.substringBefore('@'),
                        subtitle = c.lastBody.orEmpty(),
                        time = c.lastTs,
                        unread = c.unread,
                        presence = presence,
                        onClick = { onOpenChat(c.chatJid) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text("Новый чат") },
            text = {
                Column {
                    Text("Введите JID собеседника, например tsd002@openfire")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = newJid,
                        onValueChange = { newJid = it },
                        singleLine = true,
                        label = { Text("JID") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createChat(newJid) { jid ->
                        showNewChat = false
                        newJid = ""
                        onOpenChat(jid)
                    }
                }) { Text("Открыть") }
            },
            dismissButton = {
                TextButton(onClick = { showNewChat = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ChatRow(
    title: String,
    subtitle: String,
    time: Long,
    unread: Int,
    presence: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(presenceColor(presence))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shortTime(time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if (unread > 0) Badge { Text(unread.toString()) }
        }
    }
}

internal fun presenceColor(p: String): Color = when (p) {
    "online" -> Color(0xFF2E7D32)
    "away" -> Color(0xFFF9A825)
    else -> Color(0xFF9E9E9E)
}

internal fun connectionLabel(state: XmppState): String = when (state) {
    XmppState.Disconnected -> "XMPP: не подключено"
    XmppState.Connecting -> "XMPP: подключение…"
    XmppState.Connected -> "XMPP: авторизация…"
    is XmppState.Authenticated -> "XMPP: на связи (${state.jid.substringBefore('/')})"
    is XmppState.Error -> "XMPP: ошибка — ${state.message}"
}

@Composable
internal fun connectionColor(state: XmppState): Color = when (state) {
    is XmppState.Authenticated -> Color(0xFF2E7D32)
    is XmppState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

internal fun shortTime(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return if (sameDay.format(Date(ts)) == sameDay.format(Date(now))) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ts))
    }
}

@Composable
internal fun InfoCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text, Modifier.padding(16.dp))
    }
}
