package com.wakemessenger

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.local.AppDatabase
import com.wakemessenger.data.remote.ApiClient
import com.wakemessenger.data.remote.CredentialsStore
import com.wakemessenger.data.repo.AuthRepository
import com.wakemessenger.data.repo.ChatRepository
import com.wakemessenger.data.repo.SettingsRepository
import com.wakemessenger.service.NotificationHelper
import com.wakemessenger.service.WakeUpService
import com.wakemessenger.xmpp.XmppManager

/** Простейший контейнер зависимостей — один экземпляр на процесс. */
object AppGraph {

    lateinit var appContext: Context
        private set
    lateinit var log: FileLogger
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var api: ApiClient
        private set
    lateinit var credentials: CredentialsStore
        private set
    lateinit var auth: AuthRepository
        private set
    lateinit var chat: ChatRepository
        private set
    lateinit var xmpp: XmppManager
        private set
    lateinit var notifications: NotificationHelper
        private set

    @Volatile
    private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        appContext = ctx.applicationContext
        log = FileLogger(appContext)
        db = AppDatabase.build(appContext)
        settings = SettingsRepository(appContext, db.settings())
        api = ApiClient(appContext, log, settings)
        credentials = CredentialsStore(appContext)
        auth = AuthRepository(api, credentials, settings, log)
        chat = ChatRepository(db.messages(), db.users())
        xmpp = XmppManager(appContext, log, chat)
        notifications = NotificationHelper(appContext)
        ready = true
    }

    fun startService(ctx: Context) {
        val i = Intent(ctx, WakeUpService::class.java).setAction(Const.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i)
        } else {
            ctx.startService(i)
        }
    }
}

class WakeUpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.log.i("APP", "Процесс приложения запущен, версия ${BuildConfig.VERSION_NAME}")
    }
}
