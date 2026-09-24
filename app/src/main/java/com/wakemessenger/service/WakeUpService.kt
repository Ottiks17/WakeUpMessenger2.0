package com.wakemessenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wakemessenger.AppGraph
import com.wakemessenger.BuildConfig
import com.wakemessenger.R
import com.wakemessenger.core.Const
import com.wakemessenger.core.PowerSaveChecker
import com.wakemessenger.data.remote.XmppCredentials
import com.wakemessenger.data.repo.AuthRepository
import com.wakemessenger.ui.BatterySetupActivity
import com.wakemessenger.xmpp.XmppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

/**
 * Foreground Service (п. 3.1, 4.1 ТЗ).
 * Тонкий хост: держит уведомление, слушает события XMPP и сеть.
 * Логика подключения и повторов вынесена в [ConnectionSupervisor].
 */
class WakeUpService : Service() {

    private val tag = "SERVICE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log get() = AppGraph.log
    private val xmpp get() = AppGraph.xmpp

    private var started = false
    private var batteryPromptPosted = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var executor: CommandExecutor
    private lateinit var supervisor: ConnectionSupervisor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // TODO: AppGraph.init может быть медленным, а startForeground нужно успеть за ~5 с
        //  после startForegroundService. Если увидите ANR/ForegroundServiceDidNotStartInTime —
        //  показывайте минимальное уведомление ДО init.
        AppGraph.init(this)
        startInForeground()
        executor = CommandExecutor(
            this, log, AppGraph.api, AppGraph.settings, xmpp, AppGraph.notifications
        )
        supervisor = ConnectionSupervisor(
            scope = scope,
            isUp = { xmpp.isConnected },
            connectOnce = ::connectOnce,
            onRetry = { attempt, delayMs ->
                log.w(tag, "Подключение не удалось (попытка ${attempt + 1}), повтор через ${delayMs / 1000} с")
            }
        )
        log.i(tag, "Сервис создан")
        observeState()
        observeEvents()
        checkPowerSave()
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
                supervisor.forceReconnect()
                return START_STICKY
            }
        }
        if (!started) {
            started = true
            supervisor.requestConnect()
            // Регистрируем ПОСЛЕ первого запроса: onAvailable для уже поднятой сети
            // приходит сразу и не должен порождать вторую попытку.
            registerNetworkCallback()
        }
        // START_STICKY: система перезапустит сервис, если убьёт его.
        return START_STICKY
    }

    // --- Подключение ---------------------------------------------------------------------

    /**
     * Одна попытка подключения. Два режима (Настройки → «Режим подключения XMPP»):
     *  - AUTO   — п. 4.1 ТЗ: учётные данные запрашиваются у REST API;
     *  - MANUAL — REST не вызывается, креды заданы вручную.
     * Возвращает true, только если дошли до Authenticated.
     */
    private suspend fun connectOnce(): Boolean =
        try {
            if (AppGraph.settings.xmppMode() == Const.MODE_MANUAL) connectManual() else connectAuto()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(tag, "Ошибка подключения: ${e.message}", e)
            false
        }

    private suspend fun connectManual(): Boolean {
        val s = AppGraph.settings
        val host = s.manualXmppHost()
        val login = s.manualXmppLogin()
        val password = AppGraph.credentials.manualPassword()

        if (host.isBlank() || login.isBlank() || password.isNullOrBlank()) {
            AppGraph.notifications.updateService("Заполните ручные данные XMPP в настройках")
            log.e(tag, "Ручной режим: не заданы host/логин/пароль — подключение не выполняется")
            return false
        }

        log.i(tag, "Ручной режим: подключение к $host:${s.manualXmppPort()} без обращения к REST API")
        return connect(
            XmppCredentials(
                xmppHost = host,
                xmppPort = s.manualXmppPort(),
                xmppLogin = login,
                xmppPassword = password,
                apiHost = "",
                apiPort = 0
            )
        )
    }

    private suspend fun connectAuto(): Boolean {
        AppGraph.notifications.updateService("Получение учётных данных…")
        return when (val r = AppGraph.auth.obtainCredentials()) {
            is AuthRepository.Result.Fresh -> connect(r.creds)
            is AuthRepository.Result.Fallback -> {
                AppGraph.notifications.updateService("Резервные учётные данные")
                connect(r.creds)
            }
            AuthRepository.Result.None -> {
                AppGraph.notifications.updateService("Нет учётных данных, повторим позже")
                log.e(tag, "Учётные данные недоступны")
                false
            }
        }
    }

    private suspend fun connect(creds: XmppCredentials): Boolean {
        xmpp.applyPresenceMode(AppGraph.settings.presenceMode())
        // SECURITY: отключение проверки TLS разрешено только в debug-сборке.
        // В release нужен доверенный корпоративный CA (см. network_security_config / SSLContext).
        xmpp.connect(creds, trustAllCerts = BuildConfig.DEBUG && AppGraph.settings.trustAllCerts())
        // XmppManager.connect синхронный (connect + login) и не бросает исключений:
        // при ошибке он выставляет state=Error. Успех = соединение авторизовано.
        return xmpp.isConnected
    }

    // --- Наблюдатели ---------------------------------------------------------------------

    private fun observeState() {
        scope.launch {
            xmpp.state.collect { s ->
                if (s is XmppState.Authenticated) supervisor.onAuthenticated()
                val text = when (s) {
                    XmppState.Disconnected -> "Нет соединения, переподключение…"
                    XmppState.Connecting -> "Подключение к серверу…"
                    XmppState.Connected -> "Авторизация…"
                    is XmppState.Authenticated -> getString(R.string.notif_service_text)
                    is XmppState.Error -> "Ошибка: ${s.message}"
                }
                AppGraph.notifications.updateService(text)
            }
        }
        // Владение реконнектом: после обрыва установленной сессии — ReconnectionManager (Smack);
        // начальное подключение, возврат сети, смерть по ping и смена кредов — supervisor.
        // XmppManager отключает ReconnectionManager у заменённого соединения, чтобы не было двойников.
    }

    private fun observeEvents() {
        // Команды
        scope.launch {
            xmpp.commands.collect { event ->
                try {
                    executor.execute(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.e(tag, "Ошибка обработки команды: ${e.message}", e)
                }
            }
        }
        // Текстовые сообщения -> уведомление
        scope.launch {
            xmpp.texts.collect { t ->
                AppGraph.notifications.message(t.fromJid, t.nickname, t.body)
            }
        }
        // Ping сервера не прошёл (полуоткрытый TCP, роуминг Wi-Fi) -> переподключаемся сразу
        scope.launch {
            xmpp.reconnectNeeded.collect {
                log.w(tag, "Соединение мертво (ping) — переподключение")
                supervisor.forceReconnect()
            }
        }
        // Ошибка авторизации -> новые креды (п. 5.5 ТЗ), с нарастающей паузой
        scope.launch {
            xmpp.authFailed.collect {
                log.w(tag, "Ошибка авторизации XMPP — запрашиваем новые учётные данные")
                AppGraph.auth.invalidate()
                supervisor.onAuthFailed()
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.i(tag, "Сеть доступна")
                supervisor.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                log.w(tag, "Сеть потеряна")
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(cb) // API 24+ (minSdk = 24)
            networkCallback = cb
        }.onFailure { log.e(tag, "Не удалось зарегистрировать network callback: ${it.message}", it) }
    }

    // --- Энергосбережение ----------------------------------------------------------------

    private fun checkPowerSave() {
        scope.launch {
            val checks = PowerSaveChecker.check(this@WakeUpService)
            if (!checks.allPassed) {
                log.w(
                    tag,
                    "Проверки энергосбережения не пройдены (батарея=${checks.batteryOptimizationDisabled}, фон=${checks.backgroundAllowed})"
                )
                promptBatterySetup()
            } else {
                log.i(tag, "Исключения энергосбережения настроены корректно")
            }
        }
    }

    /**
     * Из фонового сервиса startActivity молча не сработает (Android 10+),
     * поэтому просим пользователя уведомлением с PendingIntent. Один раз за жизнь сервиса.
     */
    private fun promptBatterySetup() {
        if (batteryPromptPosted) return
        batteryPromptPosted = true
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(BATTERY_CHANNEL, "Настройка энергосбережения", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, BatterySetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, BATTERY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // TODO: свой значок
            .setContentTitle("Настройте энергосбережение")
            .setContentText("Без этого связь с сервером может пропадать в фоне")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(BATTERY_NOTIF_ID, n)
    }

    // --- Жизненный цикл ------------------------------------------------------------------

    override fun onDestroy() {
        log.i(tag, "Сервис останавливается")
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        scope.cancel() // сначала останавливаем supervisor, чтобы он не переподключал
        // disconnect может делать сетевой I/O — не на главном потоке
        thread(name = "xmpp-disconnect") { runCatching { xmpp.disconnect() } }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Сервис продолжает работать после смахивания приложения из списка задач.
        log.i(tag, "Приложение убрано из списка задач — сервис продолжает работу")
        super.onTaskRemoved(rootIntent)
    }

    private companion object {
        const val BATTERY_CHANNEL = "battery_setup"
        const val BATTERY_NOTIF_ID = 9001
    }
}
