package com.wakemessenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wakemessenger.AppGraph

/** Автозапуск после перезагрузки (п. 5.4 ТЗ). */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppGraph.init(context)
        AppGraph.log.i("BOOT", "Получено событие $action — запускаем сервис")
        runCatching { AppGraph.startService(context) }
            .onFailure { AppGraph.log.e("BOOT", "Не удалось запустить сервис: ${it.message}", it) }
    }
}
