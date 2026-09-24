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
            nm.createNotificationChannel(
                NotificationChannel(Const.CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Входящие сообщения чата"
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

    fun message(fromJid: String, nickname: String, body: String) {
        val n = NotificationCompat.Builder(ctx, Const.CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nickname)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openAppIntent(fromJid))
            .build()
        safeNotify(Const.NOTIF_MESSAGE_BASE + (fromJid.hashCode() and 0xFFF), n)
    }

    fun command(title: String, body: String) {
        val n = NotificationCompat.Builder(ctx, Const.CH_COMMANDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openAppIntent())
            .build()
        safeNotify(Const.NOTIF_COMMAND_BASE + (body.hashCode() and 0xFFF), n)
    }

    private fun safeNotify(id: Int, n: Notification) {
        runCatching {
            if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) nm.notify(id, n)
        }
    }
}
