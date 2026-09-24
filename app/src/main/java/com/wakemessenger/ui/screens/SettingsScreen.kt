package com.wakemessenger.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wakemessenger.AppGraph
import com.wakemessenger.core.Const
import com.wakemessenger.core.PowerSaveChecker
import com.wakemessenger.ui.LogViewModel
import com.wakemessenger.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenApi: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val ui by vm.ui.collectAsState()
    val conn by vm.connection.collectAsState()
    val http by vm.httpOnline.collectAsState()
    val ctx = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Настройки") }
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
            SectionTitle("Состояние подключения")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(connectionLabel(conn), color = connectionColor(conn))
                    Text(
                        "REST API: " + when (http) {
                            null -> "запросов ещё не было"
                            true -> "доступен"
                            false -> "недоступен"
                        }
                    )
                    Text("deviceId: ${ui.deviceId}", style = MaterialTheme.typography.bodySmall)
                    if (ui.credentialsSavedAt > 0) {
                        Text(
                            "Учётные данные сохранены: " + shortTime(ui.credentialsSavedAt),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.reconnect() }) { Text("Переподключить") }
                        OutlinedButton(onClick = onOpenApi) { Text("API-запросы") }
                    }
                }
            }

            SectionTitle("Режим подключения XMPP")
            Text(
                "Чат подключается независимо от блока «API-запросы» ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.xmppMode == Const.MODE_AUTO,
                    onClick = { vm.setXmppMode(Const.MODE_AUTO) },
                    label = { Text("Через REST API") }
                )
                FilterChip(
                    selected = ui.xmppMode == Const.MODE_MANUAL,
                    onClick = { vm.setXmppMode(Const.MODE_MANUAL) },
                    label = { Text("Вручную") }
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Доверять сертификату сервера без проверки", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Включите, если Openfire во внутренней сети использует " +
                            "самоподписанный TLS-сертификат (ошибка «Trust anchor for certification path not found»)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = ui.trustAllCerts, onCheckedChange = { vm.setTrustAllCerts(it) })
            }

            if (ui.xmppMode == Const.MODE_MANUAL) {
                OutlinedTextField(
                    value = ui.manualHost,
                    onValueChange = { vm.update(Const.S_XMPP_MANUAL_HOST, it) },
                    label = { Text("Хост Openfire") },
                    placeholder = { Text("192.168.2.201") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ui.manualPort,
                    onValueChange = { vm.update(Const.S_XMPP_MANUAL_PORT, it) },
                    label = { Text("Порт") },
                    placeholder = { Text("5222") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ui.manualLogin,
                    onValueChange = { vm.update(Const.S_XMPP_MANUAL_LOGIN, it) },
                    label = { Text("Логин (JID)") },
                    placeholder = { Text("tsd001 или tsd001@openfire") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ui.manualPassword,
                    onValueChange = { vm.update("manual_password", it) },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Скрыть" else "Показать")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Пароль хранится в зашифрованном виде на устройстве (Android Keystore), " +
                        "в лог не попадает.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Button(onClick = { vm.save { vm.reload(); vm.reconnect() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить и подключить чат")
            }

            SectionTitle("Статус присутствия")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.presence == Const.PRESENCE_ONLINE,
                    onClick = { vm.setPresence(Const.PRESENCE_ONLINE) },
                    label = { Text("Онлайн") }
                )
                FilterChip(
                    selected = ui.presence == Const.PRESENCE_INVISIBLE,
                    onClick = { vm.setPresence(Const.PRESENCE_INVISIBLE) },
                    label = { Text("Невидимка") }
                )
            }

            SectionTitle("REST API (используется блоком «API-запросы» и режимом «Через REST API» выше)")
            OutlinedTextField(
                value = ui.apiHost,
                onValueChange = { vm.update(Const.S_API_HOST, it) },
                label = { Text("Хост REST API") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ui.apiPort,
                onValueChange = { vm.update(Const.S_API_PORT, it) },
                label = { Text("Порт REST API") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ui.deviceId,
                onValueChange = { vm.update(Const.S_DEVICE_ID, it) },
                label = { Text("deviceId") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("WMS-приложение")
            OutlinedTextField(
                value = ui.wmsPackage,
                onValueChange = { vm.update(Const.S_WMS_PACKAGE, it) },
                label = { Text("Package WMS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ui.wmsAction,
                onValueChange = { vm.update(Const.S_WMS_ACTION, it) },
                label = { Text("Intent action") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.save { vm.reload() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить настройки")
            }

            SectionTitle("Энергосбережение")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (ui.batteryOk) "✓ " else "✗ ") + "Исключение из оптимизации батареи")
                    Text((if (ui.backgroundOk) "✓ " else "✗ ") + "Фоновая активность разрешена")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { PowerSaveChecker.openBatterySettings(ctx) }) {
                            Text("Батарея")
                        }
                        OutlinedButton(onClick = { PowerSaveChecker.openAutostartSettings(ctx) }) {
                            Text("Автозапуск")
                        }
                        OutlinedButton(onClick = { vm.reload() }) { Text("Обновить") }
                    }
                }
            }

            ui.lastResult?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionTitle("Лог")
            Button(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть лог-файл")
            }
            Text(
                AppGraph.log.logFile.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit, vm: LogViewModel = viewModel()) {
    val text by vm.text.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Лог") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { vm.refresh() }) { Text("Обновить") }
                OutlinedButton(onClick = { exportLog(ctx) }) { Text("Экспорт") }
                OutlinedButton(onClick = { vm.clear() }) { Text("Очистить") }
            }
            HorizontalDivider()
            Text(
                text = text.ifEmpty { "Лог пуст" },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

private fun exportLog(ctx: android.content.Context) {
    runCatching {
        val file = AppGraph.log.logFile
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "WakeUp Messenger log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(share, "Экспорт лога").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}
