
package com.wakemessenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wakemessenger.R
import com.wakemessenger.core.Const
import com.wakemessenger.data.local.MsgPriority
import com.wakemessenger.ui.CriticalAlertActivity
import com.wakemessenger.ui.MainActivity

class NotificationHelper(private val ctx: Context) {

    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_SERVICE, "Служба WakeUp", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Постоянное уведомление работающего сервиса"
                    setShowBadge(false)
                }
            )
            // Старый общий канал сообщений заменён каналами по приоритету (importance менять нельзя).
            runCatching { nm.deleteNotificationChannel(Const.CH_MESSAGES) }
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_MSG_LOW, "Низкий приоритет", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Тихие уведомления: без звука, только в шторке"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_MSG_NORMAL, "Обычный приоритет", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Уведомления со звуком"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_MSG_HIGH, "Высокий приоритет", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Звук, вибрация и всплывающее уведомление"
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_MSG_CRITICAL, "Критичные", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Открываются поверх экрана блокировки и включают экран"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_COMMANDS, "Команды", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Уведомления по командам сервера"
                }
            )
        }
    }

    private fun openAppIntent(extraJid: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extraJid != null) i.putExtra(MainActivity.EXTRA_OPEN_CHAT, extraJid)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, extraJid?.hashCode() ?: 0, i, flags)
    }

    /** Постоянное уведомление Foreground Service (п. 3.1 ТЗ). */
    fun serviceNotification(text: String = ctx.getString(R.string.notif_service_text)): Notification =
        NotificationCompat.Builder(ctx, Const.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_service_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    fun updateService(text: String) {
        runCatching { nm.notify(Const.NOTIF_SERVICE_ID, serviceNotification(text)) }
    }

    /** Как приоритет сообщения влияет на уведомление (действие команды от приоритета не зависит). */
    private data class Style(val channel: String, val compatPriority: Int, val defaults: Int, val fullScreen: Boolean)

    private fun styleFor(priority: String): Style = when (priority) {
        MsgPriority.LOW -> Style(Const.CH_MSG_LOW, NotificationCompat.PRIORITY_LOW, 0, false)
        MsgPriority.HIGH -> Style(Const.CH_MSG_HIGH, NotificationCompat.PRIORITY_HIGH, NotificationCompat.DEFAULT_ALL, false)
        MsgPriority.CRITICAL -> Style(Const.CH_MSG_CRITICAL, NotificationCompat.PRIORITY_MAX, NotificationCompat.DEFAULT_ALL, true)
        else -> Style(Const.CH_MSG_NORMAL, NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.DEFAULT_SOUND, false)
    }

    /**
     * low — тихо; normal — звук; high — звук + вибрация + heads-up; critical — то же + полноэкранный
     * показ поверх блокировки (звук один раз, без подтверждения).
     * На Android 14+ full-screen требует, чтобы пользователь не отозвал USE_FULL_SCREEN_INTENT;
     * иначе система покажет обычное heads-up уведомление.
     */
    private fun buildByPriority(
        title: String,
        body: String,
        priority: String,
        contentIntent: PendingIntent,
        chatJid: String?
    ): Notification {
        val style = styleFor(priority)
        val b = NotificationCompat.Builder(ctx, style.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(style.compatPriority)
            .setDefaults(style.defaults)
            .setContentIntent(contentIntent)
        if (style.fullScreen) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            b.setFullScreenIntent(criticalIntent(title, body, chatJid), true)
        }
        return b.build()
    }

    private fun criticalIntent(title: String, body: String, chatJid: String?): PendingIntent {
        val i = Intent(ctx, CriticalAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CriticalAlertActivity.EXTRA_TITLE, title)
            .putExtra(CriticalAlertActivity.EXTRA_BODY, body)
        if (chatJid != null) i.putExtra(MainActivity.EXTRA_OPEN_CHAT, chatJid)
        return PendingIntent.getActivity(
            ctx, CRITICAL_REQUEST_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun message(fromJid: String, nickname: String, body: String, priority: String = MsgPriority.NORMAL) {
        val n = buildByPriority(nickname, body, priority, openAppIntent(fromJid), fromJid)
        safeNotify(Const.NOTIF_MESSAGE_BASE + (fromJid.hashCode() and 0xFFF), n)
    }

    /** Уведомление по команде (update / notification). Приоритет по умолчанию — high, как раньше. */
    fun command(title: String, body: String, priority: String = MsgPriority.HIGH) {
        val n = buildByPriority(title, body, priority, openAppIntent(), null)
        safeNotify(Const.NOTIF_COMMAND_BASE + (body.hashCode() and 0xFFF), n)
    }

    /**
     * Запасной путь, если система не даёт открыть WMS из фона (Android 10+ без разрешения
     * «Поверх других окон»): уведомление, по нажатию открывает WMS с task_id.
     */
    fun wmsLaunchRequest(title: String, body: String, launch: Intent) {
        val pi = PendingIntent.getActivity(
            ctx, WMS_LAUNCH_REQUEST_CODE, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, Const.CH_COMMANDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pi)
            .build()
        safeNotify(Const.NOTIF_COMMAND_BASE + 0x1000, n)
    }

    private companion object {
        const val WMS_LAUNCH_REQUEST_CODE = 7001
        const val CRITICAL_REQUEST_CODE = 7002
    }

    private fun safeNotify(id: Int, n: Notification) {
        runCatching {
            if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) nm.notify(id, n)
        }
    }
}


