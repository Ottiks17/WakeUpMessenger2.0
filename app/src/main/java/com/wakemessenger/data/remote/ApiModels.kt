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
}

data class TaskItem(val taskId: String, val title: String, val raw: String)

data class WakeupInfo(val pingCount: Int, val oldPingCount: Int, val raw: String)
