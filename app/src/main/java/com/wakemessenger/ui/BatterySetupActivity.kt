package com.wakemessenger.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakemessenger.AppGraph
import com.wakemessenger.core.PowerChecks
import com.wakemessenger.core.PowerSaveChecker
import com.wakemessenger.ui.theme.WakeUpTheme

/**
 * Экран 4 «Настройка энергосбережения» (п. 3.2 и 8 ТЗ).
 * Открывается при первом запуске и всегда, когда проверки не пройдены.
 */
class BatterySetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContent {
            WakeUpTheme {
                Surface(Modifier.fillMaxSize()) {
                    BatterySetupScreen(
                        onDone = {
                            AppGraph.log.i("BATTERY", "Проверки энергосбережения пройдены, экран закрыт")
                            AppGraph.startService(this)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatterySetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var checks by remember { mutableStateOf(PowerSaveChecker.check(ctx)) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Для работы сервиса необходимо отключить оптимизацию батареи",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Приложение не должно быть ничем ограничено — ни батареей, ни автозапуском, ни фоновой активностью. " +
                "Названия пунктов на разных устройствах и версиях Android могут отличаться."
        )

        StatusCard(checks)

        Button(
            onClick = { PowerSaveChecker.openBatterySettings(ctx) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Открыть настройки") }

        OutlinedButton(
            onClick = {
                val opened = PowerSaveChecker.openAutostartSettings(ctx)
                if (!opened) message = "Меню автозапуска не найдено на этом устройстве"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Автозапуск (Xiaomi, Huawei, Oppo, Vivo…)") }

        OutlinedButton(
            onClick = { PowerSaveChecker.openAppDetails(ctx) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сведения о приложении") }

        Button(
            onClick = {
                checks = PowerSaveChecker.check(ctx)
                if (checks.allPassed) {
                    onDone()
                } else {
                    message = "Проверки ещё не пройдены. Разрешите работу без ограничений и повторите."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Проверить снова") }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(checks: PowerChecks) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((if (checks.batteryOptimizationDisabled) "✓ " else "✗ ") + "Исключён из оптимизации батареи")
            Text((if (checks.backgroundAllowed) "✓ " else "✗ ") + "Фоновая активность разрешена")
            Text(
                (if (checks.autostartKnownVendor) "! " else "— ") +
                    "Автозапуск: проверяется вручную в меню производителя"
            )
        }
    }
}
