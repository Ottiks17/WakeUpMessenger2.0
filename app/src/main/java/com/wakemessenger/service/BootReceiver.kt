package com.wakemessenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Автозапуск после перезагрузки и обновления приложения (п. 5.4 ТЗ).
 *
 * Только whitelist системных действий. Никакой тяжёлой инициализации на главном потоке:
 * AppGraph.init выполняет сам сервис в onCreate.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ALLOWED_ACTIONS) return

        Log.i(TAG, "Получено событие $action — запускаем сервис")
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeUpService::class.java))
        }.onFailure { Log.e(TAG, "Не удалось запустить сервис: ${it.message}", it) }
    }

    private companion object {
        const val TAG = "BOOT"
        val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
