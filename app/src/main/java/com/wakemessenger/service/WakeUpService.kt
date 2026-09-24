package com.wakemessenger.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import com.wakemessenger.AppGraph
import com.wakemessenger.core.Const
import com.wakemessenger.core.PowerSaveChecker
import com.wakemessenger.data.repo.AuthRepository
import com.wakemessenger.ui.BatterySetupActivity
import com.wakemessenger.xmpp.XmppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service (п. 3.1, 4.1 ТЗ).
 * Всегда на связи: держит XMPP-соединение, принимает сообщения и команды.
 */
class WakeUpService : Service() {

    private val tag = "SERVICE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log get() = AppGraph.log
    private val xmpp get() = AppGraph.xmpp

    private var connectJob: Job? = null
    private var started = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var executor: CommandExecutor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Уведомление показываем немедленно — иначе система убьёт сервис.
        startInForeground()
        executor = CommandExecutor(
            this, log, AppGraph.api, AppGraph.settings, xmpp, AppGraph.notifications
        )
        log.i(tag, "Сервис создан")
        observeState()
        observeEvents()
        registerNetworkCallback()
    }

    private fun startInForeground() {
        val n = AppGraph.notifications.serviceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Const.NOTIF_SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Const.NOTIF_SERVICE_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Const.ACTION_STOP -> {
                log.i(tag, "Получена команда остановки сервиса")
                stopSelf()
                return START_NOT_STICKY
            }
            Const.ACTION_RECONNECT -> {
                log.i(tag, "Принудительное переподключение")
                bootstrap(force = true)
                return START_STICKY
            }
        }
        if (!started) {
            started = true
            bootstrap(force = false)
        }
        // START_STICKY: система перезапустит сервис, если убьёт его.
        return START_STICKY
    }

    /**
     * Последовательность запуска.
     * Два независимых режима (см. Настройки → «Режим подключения XMPP»):
     *  - AUTO   — как в п. 4.1 ТЗ: учётные данные запрашиваются у REST API;
     *  - MANUAL — REST API не вызывается вообще, креды заданы вручную в Настройках.
     * Блок «вызов API-методов» (экран «API-запросы») с этим не связан:
     * он работает по требованию пользователя независимо от того, подключен ли XMPP.
     */
    private fun bootstrap(force: Boolean) {
        connectJob?.cancel()
        connectJob = scope.launch {
            // Проверка исключений энергосбережения — нужна в обоих режимах
            val checks = PowerSaveChecker.check(this@WakeUpService)
            if (!checks.allPassed) {
                log.w(
                    tag,
                    "Проверки энергосбережения не пройдены (батарея=${checks.batteryOptimizationDisabled}, фон=${checks.backgroundAllowed}) — открываем экран настройки"
                )
                openBatterySetup()
            } else {
                log.i(tag, "Исключения энергосбережения настроены корректно")
            }

            val mode = AppGraph.settings.xmppMode()
            if (mode == Const.MODE_MANUAL) {
                bootstrapManual()
            } else {
                bootstrapAuto()
            }
        }
    }

    /** Ручной режим: подключение к XMPP напрямую, REST API не участвует. */
    private suspend fun bootstrapManual() {
        val s = AppGraph.settings
        val host = s.manualXmppHost()
        val login = s.manualXmppLogin()
        val password = AppGraph.credentials.manualPassword()

        if (host.isBlank() || login.isBlank() || password.isNullOrBlank()) {
            AppGraph.notifications.updateService("Заполните ручные данные XMPP в настройках")
            log.e(tag, "Ручной режим: не заданы host/логин/пароль — подключение не выполняется")
            return
        }

        log.i(tag, "Ручной режим: подключение к $host:${s.manualXmppPort()} без обращения к REST API")
        val creds = com.wakemessenger.data.remote.XmppCredentials(
            xmppHost = host,
            xmppPort = s.manualXmppPort(),
            xmppLogin = login,
            xmppPassword = password,
            apiHost = "",
            apiPort = 0
        )
        connect(creds)
    }

    /** Автоматический режим: последовательность запуска по п. 4.1 ТЗ. */
    private suspend fun bootstrapAuto() {
        AppGraph.notifications.updateService("Получение учётных данных…")
        when (val r = AppGraph.auth.obtainCredentials()) {
            is AuthRepository.Result.Fresh -> connect(r.creds)
            is AuthRepository.Result.Fallback -> {
                AppGraph.notifications.updateService("Резервные учётные данные")
                connect(r.creds)
            }
            AuthRepository.Result.None -> {
                AppGraph.notifications.updateService("Нет учётных данных, повтор через 60 с")
                log.e(tag, "Запуск отложен: учётные данные недоступны. Повтор через 60 с")
                delay(60_000)
                bootstrap(force = true)
            }
        }
    }

    private suspend fun connect(creds: com.wakemessenger.data.remote.XmppCredentials) {
        // 4. Подключение к Openfire, 5. ожидание сообщений и команд
        xmpp.applyPresenceMode(AppGraph.settings.presenceMode())
        xmpp.connect(creds, trustAllCerts = AppGraph.settings.trustAllCerts())
    }

    private fun observeState() {
        scope.launch {
            xmpp.state.collect { s ->
                val text = when (s) {
                    XmppState.Disconnected -> "Нет соединения, переподключение…"
                    XmppState.Connecting -> "Подключение к серверу…"
                    XmppState.Connected -> "Авторизация…"
                    is XmppState.Authenticated -> getString(com.wakemessenger.R.string.notif_service_text)
                    is XmppState.Error -> "Ошибка: ${s.message}"
                }
                AppGraph.notifications.updateService(text)
            }
        }
    }

    private fun observeEvents() {
        // Команды
        scope.launch {
            xmpp.commands.collect { event ->
                runCatching { executor.execute(event) }
                    .onFailure { log.e(tag, "Ошибка обработки команды: ${it.message}", it) }
            }
        }
        // Текстовые сообщения -> уведомление
        scope.launch {
            xmpp.texts.collect { t ->
                AppGraph.notifications.message(t.fromJid, t.nickname, t.body)
            }
        }
        // Ошибка авторизации -> новые креды (п. 5.5 ТЗ)
        scope.launch {
            xmpp.authFailed.collect {
                log.w(tag, "Ошибка авторизации XMPP — запрашиваем новые учётные данные")
                AppGraph.auth.invalidate()
                bootstrap(force = true)
            }
        }
    }

    /** Переподключение сразу после восстановления сети (тест 8 из п. 9.1 ТЗ). */
    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.i(tag, "Сеть доступна")
                if (!xmpp.isConnected) bootstrap(force = true)
            }

            override fun onLost(network: Network) {
                log.w(tag, "Сеть потеряна")
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cb
            )
            networkCallback = cb
        }
    }

    private fun openBatterySetup() {
        runCatching {
            startActivity(
                Intent(this, BatterySetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onDestroy() {
        log.i(tag, "Сервис останавливается")
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        xmpp.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Сервис продолжает работать после смахивания приложения из списка задач.
        log.i(tag, "Приложение убрано из списка задач — сервис продолжает работу")
        super.onTaskRemoved(rootIntent)
    }
}
