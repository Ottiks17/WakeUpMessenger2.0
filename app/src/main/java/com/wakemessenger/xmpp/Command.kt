package com.wakemessenger.xmpp

/** Команды из п. 3.3 ТЗ. */
sealed class Command {
    data class Task(val taskId: String) : Command()
    data class Update(val version: String) : Command()
    data object Restart : Command()
    data object Ping : Command()
    data class Notification(val text: String) : Command()

    val logName: String
        get() = when (this) {
            is Task -> "task:$taskId"
            is Update -> "update:$version"
            Restart -> "restart"
            Ping -> "ping"
            is Notification -> "notification:$text"
        }
}

object CommandParser {

    /**
     * Распознаёт команду в теле XMPP-сообщения.
     * Возвращает null, если это обычный текст.
     */
    fun parse(raw: String?): Command? {
        val body = raw?.trim() ?: return null
        if (body.isEmpty()) return null

        val name = body.substringBefore(':').trim().lowercase()
        val arg = if (body.contains(':')) body.substringAfter(':').trim() else ""

        return when (name) {
            "task" -> if (arg.isNotEmpty()) Command.Task(arg) else null
            "update" -> if (arg.isNotEmpty()) Command.Update(arg) else null
            "restart" -> if (arg.isEmpty()) Command.Restart else null
            "ping" -> if (arg.isEmpty()) Command.Ping else null
            "notification" -> if (arg.isNotEmpty()) Command.Notification(arg) else null
            else -> null
        }
    }
}
