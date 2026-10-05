package com.wakemessenger.data.remote

/** Ответ GET /v1/auth/xmpp (п. 6 ТЗ). */
data class XmppCredentials(
    val xmppHost: String,
    val xmppPort: Int,
    val xmppLogin: String,
    val xmppPassword: String,
    val apiHost: String,
    val apiPort: Int
) {
    /** Домен XMPP: берётся из логина вида user@domain, иначе из хоста. */
    val domain: String
        get() = if (xmppLogin.contains('@')) xmppLogin.substringAfter('@') else xmppHost

    val username: String
        get() = xmppLogin.substringBefore('@')

    /** п. 5.5 ТЗ: пароль не выводится, даже если объект случайно попадёт в лог. */
    override fun toString(): String =
        "XmppCredentials(xmpp=$xmppHost:$xmppPort, login=$xmppLogin, password=***, api=$apiHost:$apiPort)"
}

data class WakeupInfo(val pingCount: Int, val oldPingCount: Int, val raw: String)
