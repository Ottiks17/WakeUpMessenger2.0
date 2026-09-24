package com.wakemessenger.data.repo

import com.wakemessenger.data.local.MessageDao
import com.wakemessenger.data.local.MessageEntity
import com.wakemessenger.data.local.MsgStatus
import com.wakemessenger.data.local.UserDao
import com.wakemessenger.data.local.UserEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Доступ к истории переписки и пользователям (таблицы chat_messages / chat_users). */
class ChatRepository(
    private val messages: MessageDao,
    private val users: UserDao
) {
    fun chatList() = messages.observeChatList()
    fun chat(jid: String): Flow<List<MessageEntity>> = messages.observeChat(jid)
    fun totalUnread(): Flow<Int> = messages.observeTotalUnread()
    fun users(): Flow<List<UserEntity>> = users.observeAll()
    fun user(jid: String): Flow<UserEntity?> = users.observe(jid)

    suspend fun ensureUser(jid: String, nickname: String? = null) {
        val existing = users.byJid(jid)
        if (existing == null) {
            users.upsert(UserEntity(jid = jid, nickname = nickname ?: jid.substringBefore('@')))
        } else if (!nickname.isNullOrBlank() && existing.nickname != nickname) {
            users.upsert(existing.copy(nickname = nickname))
        }
    }

    suspend fun saveIncoming(id: String?, chatJid: String, body: String, isCommand: Boolean): MessageEntity {
        ensureUser(chatJid)
        val m = MessageEntity(
            id = id ?: UUID.randomUUID().toString(),
            chatJid = chatJid,
            fromJid = chatJid,
            body = body,
            timestamp = System.currentTimeMillis(),
            outgoing = false,
            status = MsgStatus.INCOMING,
            isCommand = isCommand,
            read = false
        )
        messages.insert(m)
        return m
    }

    /** Создаёт исходящее сообщение в статусе PENDING (офлайн-очередь). */
    suspend fun enqueueOutgoing(chatJid: String, selfJid: String, body: String): MessageEntity {
        ensureUser(chatJid)
        val m = MessageEntity(
            id = UUID.randomUUID().toString(),
            chatJid = chatJid,
            fromJid = selfJid,
            body = body,
            timestamp = System.currentTimeMillis(),
            outgoing = true,
            status = MsgStatus.PENDING,
            isCommand = false,
            read = true
        )
        messages.insert(m)
        return m
    }

    suspend fun pending(): List<MessageEntity> = messages.pendingOutgoing()
    suspend fun setStatus(id: String, status: String) = messages.updateStatus(id, status)
    suspend fun markChatRead(jid: String) = messages.markChatRead(jid)
    suspend fun unreadIncoming(jid: String) = messages.unreadIncoming(jid)
    suspend fun setPresence(jid: String, presence: String) =
        users.updatePresence(jid, presence, System.currentTimeMillis())
    suspend fun setTyping(jid: String, typing: Boolean) = users.updateTyping(jid, typing)
    suspend fun allOffline() = users.allOffline()
}
