package com.wakemessenger.ui

import com.wakemessenger.data.local.MsgStatus

object StatusLabels {
    fun of(status: String): String = when (status) {
        MsgStatus.PENDING -> "в очереди"
        MsgStatus.SENT -> "отправлено"
        MsgStatus.DELIVERED -> "доставлено"
        MsgStatus.READ -> "прочитано"
        MsgStatus.FAILED -> "ошибка"
        else -> ""
    }
}

object Nav {
    const val CHATS = "chats"
    const val CHAT = "chat/{jid}"
    const val SETTINGS = "settings"
    const val LOG = "log"
    const val API = "api"
    fun chat(jid: String) = "chat/" + java.net.URLEncoder.encode(jid, "UTF-8")
}
