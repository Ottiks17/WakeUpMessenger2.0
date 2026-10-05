package com.wakemessenger.service

import android.content.Context
import android.os.PowerManager
import com.wakemessenger.core.FileLogger

/**
 * Команда restart (п. 3.3 ТЗ).
 * PowerManager.reboot требует системного разрешения REBOOT: приложение должно быть системным/
 * привилегированным (priv-app) либо получить право через MDM/вендорский механизм.
 * Если права нет — перезагрузка не выполняется, причина пишется в лог.
 */
class DeviceRebooter(private val ctx: Context, private val log: FileLogger) {
    private val tag = "REBOOT"

    fun reboot(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return try {
            log.i(tag, "Выполняется перезагрузка устройства по команде restart")
            pm.reboot("wakeup-messenger")
            true
        } catch (t: Throwable) {
            log.e(tag, "Перезагрузка невозможна: нет разрешения REBOOT (нужно системное приложение или MDM): ${t.message}")
            false
        }
    }
}
