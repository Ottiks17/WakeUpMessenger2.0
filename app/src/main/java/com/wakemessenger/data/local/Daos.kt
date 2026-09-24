package com.wakemessenger.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ChatSummary(
    val chatJid: String,
    val nickname: String?,
    val presence: String?,
    val lastBody: String?,
    val lastTs: Long,
    val unread: Int
)

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity)

    @Query("SELECT * FROM chat_messages WHERE chatJid = :jid ORDER BY timestamp ASC")
    fun observeChat(jid: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE outgoing = 1 AND status = '${MsgStatus.PENDING}' ORDER BY timestamp ASC")
    suspend fun pendingOutgoing(): List<MessageEntity>

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE chat_messages SET read = 1 WHERE chatJid = :jid AND outgoing = 0")
    suspend fun markChatRead(jid: String)

    @Query("SELECT * FROM chat_messages WHERE chatJid = :jid AND outgoing = 0 AND read = 0")
    suspend fun unreadIncoming(jid: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE outgoing = 0 AND read = 0")
    fun observeTotalUnread(): Flow<Int>

    @Query(
        """
        SELECT m.chatJid AS chatJid,
               u.nickname AS nickname,
               u.presence AS presence,
               (SELECT body FROM chat_messages x WHERE x.chatJid = m.chatJid ORDER BY x.timestamp DESC LIMIT 1) AS lastBody,
               MAX(m.timestamp) AS lastTs,
               SUM(CASE WHEN m.outgoing = 0 AND m.read = 0 THEN 1 ELSE 0 END) AS unread
        FROM chat_messages m
        LEFT JOIN chat_users u ON u.jid = m.chatJid
        GROUP BY m.chatJid
        ORDER BY lastTs DESC
        """
    )
    fun observeChatList(): Flow<List<ChatSummary>>

    @Query("DELETE FROM chat_messages")
    suspend fun clear()
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(u: UserEntity)

    @Query("SELECT * FROM chat_users ORDER BY nickname")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM chat_users WHERE jid = :jid")
    suspend fun byJid(jid: String): UserEntity?

    @Query("SELECT * FROM chat_users WHERE jid = :jid")
    fun observe(jid: String): Flow<UserEntity?>

    @Query("UPDATE chat_users SET presence = :presence, lastSeen = :ts WHERE jid = :jid")
    suspend fun updatePresence(jid: String, presence: String, ts: Long)

    @Query("UPDATE chat_users SET typing = :typing WHERE jid = :jid")
    suspend fun updateTyping(jid: String, typing: Boolean)

    @Query("UPDATE chat_users SET presence = 'offline', typing = 0")
    suspend fun allOffline()
}

@Dao
interface SettingsDao {
    @Upsert
    suspend fun put(s: SettingEntity)

    @Query("SELECT value FROM chat_settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM chat_settings")
    fun observeAll(): Flow<List<SettingEntity>>
}
