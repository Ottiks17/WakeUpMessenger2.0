package com.wakemessenger.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger

/**
 * Запуск WMS-приложения по Intent с task_id (п. 2.3 и 4.2 ТЗ).
 *
 * Android 10+ блокирует startActivity из фона (исключения нет — Activity просто не открывается).
 * Обход: разрешение «Поверх других окон» (SYSTEM_ALERT_WINDOW). Если его нет и наше UI не на
 * экране, дополнительно показывается уведомление, по нажатию на которое WMS откроется.
 */
class WmsLauncher(
    private val ctx: Context,
    private val log: FileLogger,
    private val notifications: NotificationHelper? = null
) {
    private val tag = "WMS"

    fun launch(pkg: String, action: String, taskId: String): Boolean {
        // 1. Явный Intent с action — основной путь
        val explicit = Intent(action)
            .setPackage(pkg)
            .putExtra(Const.EXTRA_TASK_ID, taskId)
            .putExtra("taskId", taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (ctx.packageManager.resolveActivity(explicit, 0) != null) {
            val ok = start(explicit, "action=$action, package=$pkg, task_id=$taskId")
            offerManualLaunchIfBlocked(explicit, taskId)
            return ok
        }

        // 2. Fallback: launcher-Intent приложения (п. 13 ТЗ)
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.putExtra(Const.EXTRA_TASK_ID, taskId)
                .putExtra("taskId", taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = start(launch, "fallback launcher-intent, package=$pkg, task_id=$taskId")
            offerManualLaunchIfBlocked(launch, taskId)
            return ok
        }

        log.e(tag, "WMS не запущено: приложение $pkg не найдено и action=$action не разрешается")
        return false
    }

    private fun start(i: Intent, desc: String): Boolean = try {
        ctx.startActivity(i)
        log.i(tag, "WMS-приложение запущено ($desc)")
        true
    } catch (t: Throwable) {
        log.e(tag, "Ошибка запуска WMS ($desc): ${t.message}", t)
        false
    }

    /** Android 10+: без «Поверх других окон» и без нашего UI на экране запуск из фона блокируется. */
    private fun backgroundStartLikelyBlocked(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !Settings.canDrawOverlays(ctx) &&
            !uiInForeground()

    private fun uiInForeground(): Boolean {
        val st = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(st)
        return st.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun offerManualLaunchIfBlocked(i: Intent, taskId: String) {
        if (!backgroundStartLikelyBlocked()) return
        log.w(
            tag,
            "Запуск WMS из фона может быть заблокирован системой (нет разрешения «Поверх других окон») — " +
                "показано уведомление для ручного запуска, task_id=$taskId"
        )
        notifications?.wmsLaunchRequest(
            "Откройте WMS",
            "Задание $taskId: нажмите, чтобы открыть WMS",
            Intent(i)
        )
    }
}
