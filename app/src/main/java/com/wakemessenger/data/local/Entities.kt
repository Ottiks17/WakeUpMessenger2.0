package com.wakemessenger.data.local

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
    val read: Boolean = false  // прочитано нами (для входящих)
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
