package com.wakemessenger.data.repo

import android.content.Context
import com.wakemessenger.core.Const
import com.wakemessenger.core.DeviceIdProvider
import com.wakemessenger.data.local.SettingEntity
import com.wakemessenger.data.local.SettingsDao

/** Настройки приложения, таблица chat_settings (п. 3.4 ТЗ). */
class SettingsRepository(private val ctx: Context, private val dao: SettingsDao) {

    suspend fun get(key: String, def: String): String = dao.get(key) ?: def

    suspend fun set(key: String, value: String) = dao.put(SettingEntity(key, value))

    suspend fun deviceId(): String {
        val stored = dao.get(Const.S_DEVICE_ID)
        if (!stored.isNullOrBlank()) return stored
        val generated = DeviceIdProvider.androidId(ctx)
        set(Const.S_DEVICE_ID, generated)
        return generated
    }

    suspend fun apiBaseUrl(): String {
        val host = get(Const.S_API_HOST, Const.DEF_API_HOST)
        val port = get(Const.S_API_PORT, Const.DEF_API_PORT)
        val normalized = if (host.startsWith("http://") || host.startsWith("https://")) host else "http://$host"
        return normalized.trimEnd('/') + ":" + port
    }

    suspend fun wmsPackage(): String = get(Const.S_WMS_PACKAGE, Const.DEF_WMS_PACKAGE)
    suspend fun wmsAction(): String = get(Const.S_WMS_ACTION, Const.DEF_WMS_ACTION)
    suspend fun presenceMode(): String = get(Const.S_PRESENCE, Const.PRESENCE_ONLINE)
    suspend fun nickname(): String = get(Const.S_NICK, "")

    // ---- Независимый блок "чат": режим получения XMPP-кредов ----
    suspend fun xmppMode(): String = get(Const.S_XMPP_MODE, Const.MODE_AUTO)
    suspend fun manualXmppHost(): String = get(Const.S_XMPP_MANUAL_HOST, "")
    suspend fun manualXmppPort(): Int = get(Const.S_XMPP_MANUAL_PORT, "5222").toIntOrNull() ?: 5222
    suspend fun manualXmppLogin(): String = get(Const.S_XMPP_MANUAL_LOGIN, "")

    /**
     * Доверять TLS-сертификату Openfire без проверки цепочки CA.
     * По умолчанию включено — типичный случай для внутренней сети склада,
     * где Openfire работает с самоподписанным сертификатом. Для сервера с
     * сертификатом от доверенного CA можно выключить в Настройках.
     */
    suspend fun trustAllCerts(): Boolean = get(Const.S_XMPP_TRUST_ALL_CERTS, "true").toBoolean()

    fun observeAll() = dao.observeAll()
}
