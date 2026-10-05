package com.wakemessenger.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище последних успешно полученных кредов (fallback, п. 4.3 ТЗ).
 *
 * ТЗ (п. 5.5) требует, чтобы пароль не хранился в SharedPreferences,
 * и одновременно (п. 4.3) требует fallback на «последние сохранённые» креды.
 * Компромисс: рабочий пароль живёт только в памяти, а резервная копия
 * кладётся в EncryptedSharedPreferences (AES-256-GCM, ключ в Android Keystore) —
 * обычные SharedPreferences не используются, в лог пароль не попадает.
 */
class CredentialsStore(ctx: Context) {

    /** true — хранилище реально зашифровано (Keystore доступен). */
    @Volatile
    private var encrypted = false

    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            ctx,
            "wakeup_creds_enc",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
        encrypted = true
        p
    }.getOrElse {
        // На ряде ТСД Keystore может быть недоступен. Fallback-креды в этом случае на диск
        // НЕ пишутся (п. 5.5 ТЗ). Обычные SharedPreferences остаются только для ручного пароля.
        ctx.getSharedPreferences("wakeup_creds_mem", Context.MODE_PRIVATE)
    }

    /** Пароль текущей сессии — только в оперативной памяти. */
    @Volatile
    var inMemory: XmppCredentials? = null
        private set

    fun save(c: XmppCredentials) {
        inMemory = c
        if (!encrypted) return   // без шифрования пароль на диск не пишем
        prefs.edit()
            .putString("xmppHost", c.xmppHost)
            .putInt("xmppPort", c.xmppPort)
            .putString("xmppLogin", c.xmppLogin)
            .putString("xmppPassword", c.xmppPassword)
            .putString("apiHost", c.apiHost)
            .putInt("apiPort", c.apiPort)
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    fun last(): XmppCredentials? {
        inMemory?.let { return it }
        if (!encrypted) return null
        val host = prefs.getString("xmppHost", null) ?: return null
        val login = prefs.getString("xmppLogin", null) ?: return null
        val pass = prefs.getString("xmppPassword", null) ?: return null
        return XmppCredentials(
            xmppHost = host,
            xmppPort = prefs.getInt("xmppPort", 5222),
            xmppLogin = login,
            xmppPassword = pass,
            apiHost = prefs.getString("apiHost", "") ?: "",
            apiPort = prefs.getInt("apiPort", 80)
        )
    }

    fun savedAt(): Long = prefs.getLong("savedAt", 0L)

    /**
     * Сбрасывает только fallback-креды из REST. Раньше prefs.edit().clear() стирал заодно и
     * ручной пароль XMPP (при любой ошибке авторизации вызывается invalidate()).
     */
    fun clear() {
        inMemory = null
        prefs.edit()
            .remove("xmppHost").remove("xmppPort").remove("xmppLogin").remove("xmppPassword")
            .remove("apiHost").remove("apiPort").remove("savedAt")
            .apply()
    }

    // ---- Ручной вход в XMPP (независимый от REST API блок) ----
    // Пароль хранится только здесь (EncryptedSharedPreferences), не в Room chat_settings.

    fun saveManualPassword(password: String) {
        prefs.edit().putString("manualXmppPassword", password).apply()
    }

    fun manualPassword(): String? = prefs.getString("manualXmppPassword", null)

    fun clearManualPassword() {
        prefs.edit().remove("manualXmppPassword").apply()
    }
}
