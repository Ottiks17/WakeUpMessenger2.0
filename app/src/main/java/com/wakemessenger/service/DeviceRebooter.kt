package com.wakemessenger.service

import android.content.Context
import android.os.PowerManager
import com.wakemessenger.core.FileLogger

/**
 * Команда restart (п. 3.3 ТЗ).
 * PowerManager.reboot требует системного разрешения REBOOT (для системного/
 * привилегированного APK на ТСД). Если его нет — пробуем su, иначе пишем ошибку.
 */
class DeviceRebooter(private val ctx: Context, private val log: FileLogger) {
    private val tag = "REBOOT"

    fun reboot(): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            log.i(tag, "Выполняется перезагрузка устройства по команде restart")
            pm.reboot("wakeup-messenger")
            return true
        } catch (t: Throwable) {
            log.e(tag, "PowerManager.reboot недоступен: ${t.message}")
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
            val code = p.waitFor()
            log.i(tag, "Перезагрузка через su, код=$code")
            code == 0
        } catch (t: Throwable) {
            log.e(tag, "Перезагрузка невозможна: нет разрешения REBOOT и нет su (${t.message})")
            false
        }
    }
}
