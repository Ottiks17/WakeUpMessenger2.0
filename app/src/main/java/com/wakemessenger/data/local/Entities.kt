
package com.wakemessenger.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Статусы исходящего сообщения (офлайн-очередь, п. 4.3 ТЗ). */
object MsgStatus {
    const val PENDING = "PENDING"     // в очереди, не отправлено (нет связи)
    const val SENT = "SENT"           // отдано серверу
    const val DELIVERED = "DELIVERED" // XEP-0184 receipt
    const val READ = "READ"           // XEP-0333 displayed
    const val FAILED = "FAILED"
    const val INCOMING = "INCOMING"
}

/** Приоритет сообщения: приходит в расширении <priority xmlns="urn:wakeup:msg:0" level="..."/>. */
object MsgPriority {
    const val LOW = "low"
    const val NORMAL = "normal"
    const val HIGH = "high"
    const val CRITICAL = "critical"

    /** Нет значения или неизвестное значение -> normal. */
    fun parse(raw: String?): String = when (raw?.trim()?.lowercase()) {
        LOW -> LOW
        HIGH -> HIGH
        CRITICAL -> CRITICAL
        else -> NORMAL
    }
}

@Entity(
    tableName = "chat_messages",
    indices = [Index("chatJid"), Index("timestamp")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatJid: String,       // bare JID собеседника
    val fromJid: String,       // кто отправил
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val status: String,
    val isCommand: Boolean = false,
    val read: Boolean = false, // прочитано нами (для входящих)
    // defaultValue должен совпадать с DEFAULT в MIGRATION_1_2 (строка в кавычках — SQL-литерал)
    @ColumnInfo(defaultValue = "'normal'") val priority: String = MsgPriority.NORMAL
)

@Entity(tableName = "chat_users")
data class UserEntity(
    @PrimaryKey val jid: String,       // bare JID
    val nickname: String,
    val presence: String = "offline",  // online | away | offline
    val lastSeen: Long = 0L,
    val typing: Boolean = false
)

@Entity(tableName = "chat_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)


