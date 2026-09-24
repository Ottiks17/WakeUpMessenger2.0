package com.wakemessenger.service

import android.content.Context
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.remote.ApiClient
import com.wakemessenger.data.repo.SettingsRepository
import com.wakemessenger.xmpp.Command
import com.wakemessenger.xmpp.CommandEvent
import com.wakemessenger.xmpp.XmppManager

/** Обработка команд (п. 3.3 и 4.2 ТЗ). */
class CommandExecutor(
    ctx: Context,
    private val log: FileLogger,
    private val api: ApiClient,
    private val settings: SettingsRepository,
    private val xmpp: XmppManager,
    private val notifications: NotificationHelper
) {
    private val tag = "CMD"
    private val wms = WmsLauncher(ctx, log)
    private val rebooter = DeviceRebooter(ctx, log)

    suspend fun execute(event: CommandEvent) {
        log.i(tag, "Выполнение команды ${event.command.logName} от ${event.fromJid}")
        when (val c = event.command) {
            is Command.Task -> handleTask(c.taskId)
            is Command.Update -> notifications.command(
                "Доступно обновление WMS",
                "Рекомендуется обновить WMS-приложение до версии ${c.version}"
            )
            Command.Restart -> rebooter.reboot()
            Command.Ping -> {
                val ok = xmpp.sendRaw(event.fromJid, "pong")
                log.i(tag, "Ответ pong -> ${event.fromJid}: ${if (ok) "отправлен" else "ошибка"}")
            }
            is Command.Notification -> notifications.command("WakeUp Messenger", c.text)
        }
    }

    /**
     * task: POST /v1/wakeup/confirm -> запуск WMS через Intent с task_id.
     * Ошибка подтверждения только логируется — WMS всё равно запускается (п. 4.3 ТЗ).
     */
    private suspend fun handleTask(taskId: String) {
        val deviceId = settings.deviceId()
        try {
            val ok = api.confirmWakeup(deviceId, taskId)
            log.i(tag, "Подтверждение пробуждения task_id=$taskId: ${if (ok) "принято" else "отклонено сервером"}")
        } catch (t: Throwable) {
            log.e(tag, "Ошибка подтверждения пробуждения task_id=$taskId: ${t.message} — WMS всё равно запускается")
        }
        val launched = wms.launch(settings.wmsPackage(), settings.wmsAction(), taskId)
        if (!launched) {
            notifications.command("Не удалось запустить WMS", "Задание $taskId. Проверьте настройки пакета WMS.")
        }
    }
}
