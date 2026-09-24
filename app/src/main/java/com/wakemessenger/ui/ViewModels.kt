package com.wakemessenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wakemessenger.AppGraph
import com.wakemessenger.core.Const
import com.wakemessenger.data.local.ChatSummary
import com.wakemessenger.data.local.MessageEntity
import com.wakemessenger.data.local.UserEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel : ViewModel() {
    val chats: StateFlow<List<ChatSummary>> =
        AppGraph.chat.chatList().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val users: StateFlow<List<UserEntity>> =
        AppGraph.chat.users().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connection = AppGraph.xmpp.state

    fun createChat(jid: String, onDone: (String) -> Unit) {
        val normalized = jid.trim()
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            AppGraph.chat.ensureUser(normalized)
            onDone(normalized)
        }
    }
}

class ChatViewModel(private val jid: String) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> =
        AppGraph.chat.chat(jid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peer: StateFlow<UserEntity?> =
        AppGraph.chat.user(jid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connection = AppGraph.xmpp.state

    private var typingSent = false

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            AppGraph.xmpp.sendText(jid, body)
            AppGraph.xmpp.sendTyping(jid, false)
            typingSent = false
        }
    }

    fun onInputChanged(value: String) {
        val composing = value.isNotEmpty()
        if (composing == typingSent) return
        typingSent = composing
        viewModelScope.launch { AppGraph.xmpp.sendTyping(jid, composing) }
    }

    fun markRead() {
        viewModelScope.launch { AppGraph.xmpp.markRead(jid) }
    }
}

data class SettingsUi(
    val deviceId: String = "",
    val apiHost: String = "",
    val apiPort: String = "",
    val wmsPackage: String = "",
    val wmsAction: String = "",
    val presence: String = Const.PRESENCE_ONLINE,
    val batteryOk: Boolean = false,
    val backgroundOk: Boolean = false,
    val credentialsSavedAt: Long = 0L,
    val busyMessage: String? = null,
    val lastResult: String? = null,
    // Независимый блок "чат": режим подключения к XMPP
    val xmppMode: String = Const.MODE_AUTO,
    val manualHost: String = "",
    val manualPort: String = "5222",
    val manualLogin: String = "",
    val manualPassword: String = "",
    val trustAllCerts: Boolean = true
)

class SettingsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui

    val connection = AppGraph.xmpp.state
    val httpOnline = AppGraph.api.online

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val s = AppGraph.settings
            val ctx = AppGraph.appContext
            _ui.value = _ui.value.copy(
                deviceId = s.deviceId(),
                apiHost = s.get(Const.S_API_HOST, Const.DEF_API_HOST),
                apiPort = s.get(Const.S_API_PORT, Const.DEF_API_PORT),
                wmsPackage = s.wmsPackage(),
                wmsAction = s.wmsAction(),
                presence = s.presenceMode(),
                batteryOk = com.wakemessenger.core.PowerSaveChecker.isIgnoringBatteryOptimizations(ctx),
                backgroundOk = com.wakemessenger.core.PowerSaveChecker.isBackgroundAllowed(ctx),
                credentialsSavedAt = AppGraph.credentials.savedAt(),
                xmppMode = s.xmppMode(),
                manualHost = s.manualXmppHost(),
                manualPort = s.manualXmppPort().toString(),
                manualLogin = s.manualXmppLogin(),
                manualPassword = AppGraph.credentials.manualPassword().orEmpty(),
                trustAllCerts = s.trustAllCerts()
            )
        }
    }

    fun update(field: String, value: String) {
        _ui.value = when (field) {
            Const.S_API_HOST -> _ui.value.copy(apiHost = value)
            Const.S_API_PORT -> _ui.value.copy(apiPort = value)
            Const.S_WMS_PACKAGE -> _ui.value.copy(wmsPackage = value)
            Const.S_WMS_ACTION -> _ui.value.copy(wmsAction = value)
            Const.S_DEVICE_ID -> _ui.value.copy(deviceId = value)
            Const.S_XMPP_MANUAL_HOST -> _ui.value.copy(manualHost = value)
            Const.S_XMPP_MANUAL_PORT -> _ui.value.copy(manualPort = value)
            Const.S_XMPP_MANUAL_LOGIN -> _ui.value.copy(manualLogin = value)
            "manual_password" -> _ui.value.copy(manualPassword = value)
            else -> _ui.value
        }
    }

    fun setXmppMode(mode: String) {
        _ui.value = _ui.value.copy(xmppMode = mode)
        viewModelScope.launch { AppGraph.settings.set(Const.S_XMPP_MODE, mode) }
    }

    fun setTrustAllCerts(value: Boolean) {
        _ui.value = _ui.value.copy(trustAllCerts = value)
        viewModelScope.launch { AppGraph.settings.set(Const.S_XMPP_TRUST_ALL_CERTS, value.toString()) }
        // Переключатель влияет на TLS-соединение сразу — переподключаем чат,
        // чтобы не показывать устаревшую ошибку до следующего ручного нажатия.
        reconnect()
    }

    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val u = _ui.value
            val s = AppGraph.settings
            s.set(Const.S_API_HOST, u.apiHost.trim())
            s.set(Const.S_API_PORT, u.apiPort.trim().ifEmpty { Const.DEF_API_PORT })
            s.set(Const.S_WMS_PACKAGE, u.wmsPackage.trim())
            s.set(Const.S_WMS_ACTION, u.wmsAction.trim())
            s.set(Const.S_DEVICE_ID, u.deviceId.trim())
            // Независимый блок "чат": ручные XMPP-креды
            s.set(Const.S_XMPP_MANUAL_HOST, u.manualHost.trim())
            s.set(Const.S_XMPP_MANUAL_PORT, u.manualPort.trim().ifEmpty { "5222" })
            s.set(Const.S_XMPP_MANUAL_LOGIN, u.manualLogin.trim())
            if (u.manualPassword.isNotEmpty()) {
                AppGraph.credentials.saveManualPassword(u.manualPassword)
            }
            _ui.value = u.copy(lastResult = "Настройки сохранены")
            onSaved()
        }
    }

    fun setPresence(mode: String) {
        viewModelScope.launch {
            AppGraph.settings.set(Const.S_PRESENCE, mode)
            AppGraph.xmpp.applyPresenceMode(mode)
            _ui.value = _ui.value.copy(presence = mode)
        }
    }

    /** Переподключить XMPP-чат — использует режим (авто/вручную), заданный в этом же экране. */
    fun reconnect() {
        AppGraph.startService(AppGraph.appContext)
        AppGraph.appContext.startService(
            android.content.Intent(AppGraph.appContext, com.wakemessenger.service.WakeUpService::class.java)
                .setAction(Const.ACTION_RECONNECT)
        )
        _ui.value = _ui.value.copy(lastResult = "Переподключение запущено")
    }
}

// ------------------------------------------------------------------------------------
// Независимый блок "вызов API-методов" — не связан с состоянием XMPP-чата.
// Каждый вызов выполняется по требованию пользователя и не влияет на подключение к Openfire.
// ------------------------------------------------------------------------------------

data class ApiUi(
    val deviceId: String = "",
    val taskId: String = "",
    val busy: String? = null,
    val result: String? = null,
    val lastFetched: com.wakemessenger.data.remote.XmppCredentials? = null
)

class ApiViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ApiUi())
    val ui: StateFlow<ApiUi> = _ui

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(deviceId = AppGraph.settings.deviceId())
        }
    }

    fun setDeviceId(v: String) {
        _ui.value = _ui.value.copy(deviceId = v)
    }

    fun setTaskId(v: String) {
        _ui.value = _ui.value.copy(taskId = v)
    }

    fun callAuth() = run("GET /v1/auth/xmpp") {
        val c = AppGraph.api.fetchCredentials(_ui.value.deviceId)
        _ui.value = _ui.value.copy(lastFetched = c)
        "host=${c.xmppHost}:${c.xmppPort}\nlogin=${c.xmppLogin}\npassword=${"•".repeat(c.xmppPassword.length)}"
    }

    fun callConfirm() = run("POST /v1/wakeup/confirm") {
        val ok = AppGraph.api.confirmWakeup(_ui.value.deviceId, _ui.value.taskId)
        if (ok) "OK — сервер подтвердил" else "Сервер ответил не 2xx"
    }

    fun callTasks() = run("GET /v1/tasks") {
        val tasks = AppGraph.api.tasks(_ui.value.deviceId)
        if (tasks.isEmpty()) "Заданий нет" else tasks.joinToString("\n") { "• ${it.taskId}  ${it.title}" }
    }

    fun callInfo() = run("GET /wakeup/info") {
        val i = AppGraph.api.wakeupInfo(_ui.value.deviceId)
        "pingCount=${i.pingCount}, oldPingCount=${i.oldPingCount}"
    }

    fun callReset() = run("DELETE /wakeup/reset") {
        AppGraph.api.wakeupReset(_ui.value.deviceId)
        "Счётчик сброшен"
    }

    /** Перенести только что полученные из /v1/auth/xmpp данные в ручной вход XMPP-чата. */
    fun applyFetchedAsManual(onApplied: () -> Unit) {
        val c = _ui.value.lastFetched ?: return
        viewModelScope.launch {
            val s = AppGraph.settings
            s.set(Const.S_XMPP_MANUAL_HOST, c.xmppHost)
            s.set(Const.S_XMPP_MANUAL_PORT, c.xmppPort.toString())
            s.set(Const.S_XMPP_MANUAL_LOGIN, c.xmppLogin)
            AppGraph.credentials.saveManualPassword(c.xmppPassword)
            s.set(Const.S_XMPP_MODE, Const.MODE_MANUAL)
            onApplied()
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = "$label…", result = null)
            val r = runCatching { block() }
            _ui.value = _ui.value.copy(
                busy = null,
                result = label + "\n" + r.getOrElse { "Ошибка: ${it.message}" }
            )
        }
    }
}

class LogViewModel : ViewModel() {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _text.value = AppGraph.log.readLog() }
    }

    fun clear() {
        viewModelScope.launch {
            AppGraph.log.clear()
            _text.value = ""
        }
    }

    fun logFilePath(): String = AppGraph.log.logFile.absolutePath
}
