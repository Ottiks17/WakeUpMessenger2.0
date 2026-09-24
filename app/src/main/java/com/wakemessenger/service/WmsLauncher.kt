package com.wakemessenger.service

import android.content.Context
import android.content.Intent
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger

/** Запуск WMS-приложения по Intent с task_id (п. 2.3 и 4.2 ТЗ). */
class WmsLauncher(
    private val ctx: Context,
    private val log: FileLogger
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
            return start(explicit, "action=$action, package=$pkg, task_id=$taskId")
        }

        // 2. Fallback: launcher-Intent приложения (п. 13 ТЗ)
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.putExtra(Const.EXTRA_TASK_ID, taskId)
                .putExtra("taskId", taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return start(launch, "fallback launcher-intent, package=$pkg, task_id=$taskId")
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
}
