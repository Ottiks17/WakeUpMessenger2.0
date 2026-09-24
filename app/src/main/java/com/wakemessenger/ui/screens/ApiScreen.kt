package com.wakemessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wakemessenger.ui.ApiViewModel

/**
 * Независимый блок «вызов API-методов».
 * Работает без какой-либо связи с XMPP-чатом: адрес REST API берётся из
 * Настроек → «REST API», но сами вызовы здесь ничего не подключают и не отключают в чате.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiScreen(onBack: () -> Unit, vm: ApiViewModel = viewModel()) {

    val ui by vm.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("API-запросы") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Прямые вызовы REST API — независимо от того, подключен ли чат к Openfire. " +
                    "Адрес сервера берётся из Настроек → «REST API».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            OutlinedTextField(
                value = ui.deviceId,
                onValueChange = { vm.setDeviceId(it) },
                label = { Text("deviceId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("GET /v1/auth/xmpp")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.callAuth() }) { Text("Запросить") }
                if (ui.lastFetched != null) {
                    TextButton(onClick = { vm.applyFetchedAsManual(onBack) }) {
                        Text("Использовать для чата")
                    }
                }
            }

            SectionTitle("POST /v1/wakeup/confirm")
            OutlinedTextField(
                value = ui.taskId,
                onValueChange = { vm.setTaskId(it) },
                label = { Text("taskId") },
                placeholder = { Text("12345") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.callConfirm() }) { Text("Подтвердить") }

            SectionTitle("GET /v1/tasks")
            Button(onClick = { vm.callTasks() }) { Text("Получить задания") }

            SectionTitle("Диагностика счётчика (п. 9.4 ТЗ)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.callInfo() }) { Text("/wakeup/info") }
                Button(onClick = { vm.callReset() }) { Text("/wakeup/reset") }
            }

            ui.busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ui.result?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}
