package com.wakemessenger.xmpp

import android.content.Context
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.local.MsgStatus
import com.wakemessenger.data.remote.XmppCredentials
import com.wakemessenger.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.packet.PresenceBuilder
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.TLSUtils
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.ChatStateListener
import org.jivesoftware.smackx.chatstates.ChatStateManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.atomic.AtomicBoolean

sealed class XmppState {
    data object Disconnected : XmppState()
    data object Connecting : XmppState()
    data object Connected : XmppState()
    data class Authenticated(val jid: String) : XmppState()
    data class Error(val message: String) : XmppState()
}

/** Событие «пришла команда» — обрабатывается сервисом. */
data class CommandEvent(val command: Command, val fromJid: String, val rawBody: String)

/** Событие «пришло текстовое сообщение» — для уведомления пользователя. */
data class TextEvent(val fromJid: String, val nickname: String, val body: String)

/**
 * XMPP-клиент на Smack 4.4.x (п. 5.1 ТЗ).
 * Автопереподключение с exponential backoff, офлайн-очередь исходящих,
 * индикатор набора текста (XEP-0085), отметки о доставке/прочтении (XEP-0184/0333).
 */
class XmppManager(
    private val ctx: Context,
    private val log: FileLogger,
    private val repo: ChatRepository
) {
    private val tag = "XMPP"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val initialized = AtomicBoolean(false)

    @Volatile
    private var connection: XMPPTCPConnection? = null

    @Volatile
    private var creds: XmppCredentials? = null

    private val _state = MutableStateFlow<XmppState>(XmppState.Disconnected)
    val state: StateFlow<XmppState> = _state

    private val _commands = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 32)
    val commands: SharedFlow<CommandEvent> = _commands

    private val _texts = MutableSharedFlow<TextEvent>(extraBufferCapacity = 32)
    val texts: SharedFlow<TextEvent> = _texts

    /** Событие «сервер отверг логин/пароль» — сервис запросит новые креды (п. 5.5 ТЗ). */
    private val _authFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val authFailed: SharedFlow<Unit> = _authFailed

    val selfJid: String get() = creds?.let { "${it.username}@${it.domain}" } ?: ""

    val isConnected: Boolean get() = connection?.isAuthenticated == true

    // ------------------------------------------------------------------ connect

    /**
     * @param trustAllCerts не проверять цепочку CA (самоподписанный сертификат Openfire)
     * @param securityMode  "ifpossible" (TLS, но без TLS если сервер не предлагает),
     *                      "disabled" (TLS не используется вообще — крайняя мера,
     *                      если сервер не отдаёт валидный TLS-стек даже с trustAllCerts)
     */
    suspend fun connect(
        c: XmppCredentials,
        trustAllCerts: Boolean = true,
        securityMode: String = "ifpossible"
    ) = withContext(Dispatchers.IO) {
        if (initialized.compareAndSet(false, true)) {
            runCatching { AndroidSmackInitializer.initialize(ctx.applicationContext) }
                .onFailure { log.e(tag, "AndroidSmackInitializer: ${it.message}") }
        }
        disconnect(silent = true)
        creds = c
        _state.value = XmppState.Connecting
        log.i(tag, "Подключение к ${c.xmppHost}:${c.xmppPort}, домен=${c.domain}, логин=${c.username}, пароль=${log.mask(c.xmppPassword)}")

        try {
            val mode = if (securityMode == "disabled") {
                ConnectionConfiguration.SecurityMode.disabled
            } else {
                ConnectionConfiguration.SecurityMode.ifpossible
            }

            val configBuilder = XMPPTCPConnectionConfiguration.builder()
                .setHost(c.xmppHost)
                .setPort(c.xmppPort)
                .setXmppDomain(c.domain)
                .setUsernameAndPassword(c.username, c.xmppPassword)
                .setSecurityMode(mode)
                .setResource("wakeup")
                .setSendPresence(false)
                .setCompressionEnabled(false)

            if (mode == ConnectionConfiguration.SecurityMode.disabled) {
                log.w(tag, "TLS отключён полностью — соединение и авторизация идут в открытом виде")
            } else if (trustAllCerts) {
                // Самоподписанный сертификат Openfire во внутренней сети склада — Android по
                // умолчанию его не доверяет. Используем штатный механизм Smack (TLSUtils),
                // а не самописный TrustManager: на Android голый X509TrustManager,
                // собранный вручную через SSLContext.init(), Conscrypt может завернуть и
                // всё равно прогнать через системную проверку CA (TrustManagerImpl) —
                // TLSUtils.acceptAllCertificates() учитывает эту особенность платформы.
                log.w(tag, "Проверка TLS-сертификата отключена (доверие самоподписанному сертификату)")
                TLSUtils.acceptAllCertificates(configBuilder)
                TLSUtils.disableHostnameVerificationForTlsCertificates(configBuilder)
            }

            val config = configBuilder.build()

            val conn = XMPPTCPConnection(config)
            conn.replyTimeout = 10_000L
            connection = conn

            // Автопереподключение с нарастающей задержкой (exponential backoff), п. 4.3 ТЗ
            ReconnectionManager.getInstanceFor(conn).apply {
                setReconnectionPolicy(ReconnectionManager.ReconnectionPolicy.RANDOM_INCREASING_DELAY)
                enableAutomaticReconnection()
            }

            conn.addConnectionListener(connectionListener)
            attachListeners(conn)

            conn.connect()
            conn.login()
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            log.e(tag, "Ошибка подключения: $msg", t)
            _state.value = XmppState.Error(msg)
            if (isAuthError(t)) scope.launch { _authFailed.emit(Unit) }
        }
    }

    private fun isAuthError(t: Throwable): Boolean {
        val s = (t.message ?: "") + (t.cause?.message ?: "")
        return t is org.jivesoftware.smack.sasl.SASLErrorException ||
            s.contains("not-authorized", true) ||
            s.contains("SASL", true)
    }

    fun disconnect(silent: Boolean = false) {
        val conn = connection ?: return
        runCatching {
            if (conn.isConnected) {
                runCatching { conn.sendStanza(presenceStanza(Presence.Type.unavailable)) }
                conn.disconnect()
            }
        }
        connection = null
        if (!silent) {
            log.i(tag, "XMPP-соединение закрыто")
            _state.value = XmppState.Disconnected
            scope.launch { repo.allOffline() }
        }
    }

    // ---------------------------------------------------------------- listeners

    private val connectionListener = object : ConnectionListener {
        override fun connected(connection: XMPPConnection) {
            log.i(tag, "TCP-соединение установлено")
            _state.value = XmppState.Connected
        }

        override fun authenticated(connection: XMPPConnection, resumed: Boolean) {
            val jid = connection.user?.toString() ?: selfJid
            log.i(tag, "Авторизация успешна: $jid (возобновление=$resumed)")
            _state.value = XmppState.Authenticated(jid)
            scope.launch {
                applyPresenceMode(currentPresenceMode)
                flushOfflineQueue()
            }
        }

        override fun connectionClosed() {
            log.i(tag, "Соединение закрыто")
            _state.value = XmppState.Disconnected
            scope.launch { repo.allOffline() }
        }

        override fun connectionClosedOnError(e: Exception) {
            log.e(tag, "Соединение разорвано с ошибкой: ${e.message}")
            _state.value = XmppState.Error(e.message ?: "ошибка соединения")
            scope.launch { repo.allOffline() }
            if (isAuthError(e)) scope.launch { _authFailed.emit(Unit) }
        }
    }

    private fun attachListeners(conn: XMPPTCPConnection) {
        // Входящие сообщения
        ChatManager.getInstanceFor(conn).addIncomingListener { from, message, _ ->
            handleIncoming(from, message)
        }

        // Ростер и статусы собеседников
        val roster = Roster.getInstanceFor(conn)
        runCatching { roster.subscriptionMode = Roster.SubscriptionMode.accept_all }
        roster.addRosterListener(object : RosterListener {
            override fun entriesAdded(addresses: MutableCollection<Jid>) = syncRoster(roster)
            override fun entriesUpdated(addresses: MutableCollection<Jid>) = syncRoster(roster)
            override fun entriesDeleted(addresses: MutableCollection<Jid>) = syncRoster(roster)
            override fun presenceChanged(presence: Presence) {
                val jid = presence.from?.asBareJid()?.toString() ?: return
                val value = when {
                    presence.type == Presence.Type.unavailable -> "offline"
                    presence.mode == Presence.Mode.away || presence.mode == Presence.Mode.xa -> "away"
                    presence.isAvailable -> "online"
                    else -> "offline"
                }
                scope.launch {
                    repo.ensureUser(jid)
                    repo.setPresence(jid, value)
                }
            }
        })

        // Индикатор набора текста
        runCatching {
            ChatStateManager.getInstance(conn).addChatStateListener(object : ChatStateListener {
                override fun stateChanged(chat: Chat, state: ChatState, message: Message) {
                    val jid = chat.xmppAddressOfChatPartner.asBareJid().toString()
                    scope.launch { repo.setTyping(jid, state == ChatState.composing) }
                }
            })
        }

        // Отметки о доставке (XEP-0184)
        runCatching {
            DeliveryReceiptManager.getInstanceFor(conn).apply {
                autoAddDeliveryReceiptRequests()
                setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.always)
                addReceiptReceivedListener(ReceiptReceivedListener { _, _, receiptId, _ ->
                    scope.launch { repo.setStatus(receiptId, MsgStatus.DELIVERED) }
                })
            }
        }
    }

    private fun syncRoster(roster: Roster) {
        scope.launch {
            runCatching {
                roster.entries.forEach { e ->
                    val jid = e.jid.asBareJid().toString()
                    repo.ensureUser(jid, e.name)
                    val p = roster.getPresence(e.jid)
                    repo.setPresence(jid, if (p.isAvailable) "online" else "offline")
                }
            }
        }
    }

    // ----------------------------------------------------------------- incoming

    private fun handleIncoming(from: EntityBareJid, message: Message) {
        val body = message.body ?: return
        val jid = from.asBareJid().toString()
        val command = CommandParser.parse(body)

        log.i(tag, "Получено XMPP-сообщение от $jid: ${if (command != null) "команда ${command.logName}" else "текст (${body.length} симв.)"}")

        scope.launch {
            repo.ensureUser(jid)
            repo.saveIncoming(message.stanzaId, jid, body, command != null)
            if (command != null) {
                _commands.emit(CommandEvent(command, jid, body))
            } else {
                val nick = jid.substringBefore('@')
                _texts.emit(TextEvent(jid, nick, body))
            }
            // Отметка «прочитано» отправляется из экрана переписки (markRead)
            handleDisplayedMarker(message)
        }
    }

    private suspend fun handleDisplayedMarker(message: Message) {
        runCatching {
            val displayed = message.getExtension(ChatMarkersElements.DisplayedExtension::class.java)
            if (displayed != null) repo.setStatus(displayed.id, MsgStatus.READ)
        }
    }

    // ----------------------------------------------------------------- outgoing

    /**
     * Отправка текста. Сообщение всегда сначала попадает в Room со статусом PENDING,
     * поэтому при отсутствии связи оно уйдёт после переподключения (офлайн-очередь).
     */
    suspend fun sendText(toJid: String, text: String) = withContext(Dispatchers.IO) {
        val entity = repo.enqueueOutgoing(toJid, selfJid, text)
        deliver(entity.id, toJid, text)
    }

    private suspend fun deliver(id: String, toJid: String, text: String): Boolean {
        val conn = connection
        if (conn == null || !conn.isAuthenticated) {
            log.w(tag, "Нет соединения — сообщение $id остаётся в офлайн-очереди")
            return false
        }
        return runCatching {
            val builder = MessageBuilder.buildMessage(id)
                .to(JidCreate.entityBareFrom(toJid))
                .ofType(Message.Type.chat)
                .setBody(text)
            runCatching { DeliveryReceiptRequest.addTo(builder) }
            runCatching { builder.addExtension(ChatMarkersElements.MarkableExtension.INSTANCE) }
            conn.sendStanza(builder.build())
            repo.setStatus(id, MsgStatus.SENT)
            true
        }.onFailure {
            log.e(tag, "Ошибка отправки сообщения $id: ${it.message}")
            repo.setStatus(id, MsgStatus.PENDING)
        }.getOrDefault(false)
    }

    /** Ответ pong на команду ping (п. 4.2 ТЗ). */
    suspend fun sendRaw(toJid: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext false
        runCatching {
            val msg = MessageBuilder.buildMessage()
                .to(JidCreate.entityBareFrom(toJid))
                .ofType(Message.Type.chat)
                .setBody(text)
                .build()
            conn.sendStanza(msg)
            true
        }.getOrElse {
            log.e(tag, "Ошибка отправки '$text' -> $toJid: ${it.message}")
            false
        }
    }

    private suspend fun flushOfflineQueue() {
        val pending = repo.pending()
        if (pending.isEmpty()) return
        log.i(tag, "Офлайн-очередь: отправка ${pending.size} сообщ.")
        pending.forEach { deliver(it.id, it.chatJid, it.body) }
    }

    // ------------------------------------------------------------ chat state/UI

    suspend fun sendTyping(toJid: String, composing: Boolean) = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext
        runCatching {
            val chat = ChatManager.getInstanceFor(conn).chatWith(JidCreate.entityBareFrom(toJid))
            ChatStateManager.getInstance(conn)
                .setCurrentState(if (composing) ChatState.composing else ChatState.active, chat)
        }
    }

    /** Отправка отметок о прочтении для всех непрочитанных входящих чата. */
    suspend fun markRead(jid: String) = withContext(Dispatchers.IO) {
        val unread = repo.unreadIncoming(jid)
        repo.markChatRead(jid)
        val conn = connection ?: return@withContext
        if (!conn.isAuthenticated) return@withContext
        unread.forEach { m ->
            runCatching {
                val msg = MessageBuilder.buildMessage()
                    .to(JidCreate.entityBareFrom(jid))
                    .ofType(Message.Type.chat)
                    .addExtension(ChatMarkersElements.DisplayedExtension(m.id))
                    .build()
                conn.sendStanza(msg)
            }
        }
    }

    // ------------------------------------------------------------------ presence

    @Volatile
    private var currentPresenceMode: String = Const.PRESENCE_ONLINE

    /** online — обычное присутствие; invisible — «невидимка» (unavailable при живом соединении). */
    suspend fun applyPresenceMode(mode: String) = withContext(Dispatchers.IO) {
        currentPresenceMode = mode
        val conn = connection ?: return@withContext
        if (!conn.isAuthenticated) return@withContext
        runCatching {
            val type = if (mode == Const.PRESENCE_INVISIBLE) Presence.Type.unavailable else Presence.Type.available
            conn.sendStanza(presenceStanza(type))
            log.i(tag, "Статус присутствия: $mode")
        }
    }

    private fun presenceStanza(type: Presence.Type): Presence =
        PresenceBuilder.buildPresence().ofType(type).build()
}
