package com.wakemessenger.xmpp

import android.content.Context
import com.wakemessenger.BuildConfig
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.local.MsgStatus
import com.wakemessenger.data.remote.XmppCredentials
import com.wakemessenger.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smack.util.TLSUtils
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements
import org.jivesoftware.smackx.chatstates.ChatState
import org.jivesoftware.smackx.chatstates.ChatStateListener
import org.jivesoftware.smackx.chatstates.ChatStateManager
import org.jivesoftware.smackx.delay.DelayInformationManager
import org.jivesoftware.smackx.ping.PingFailedListener
import org.jivesoftware.smackx.ping.PingManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

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
 *
 * Изменения относительно исходной версии — см. пометки «FIX».
 */
class XmppManager(
    private val ctx: Context,
    private val log: FileLogger,
    private val repo: ChatRepository
) {
    private val tag = "XMPP"

    // FIX: раньше CoroutineScope(Dispatchers.IO) — обычный Job: одно необработанное исключение
    // в любом scope.launch (например, ошибка Room) либо роняло приложение, либо навсегда
    // отменяло scope, и входящие сообщения переставали обрабатываться.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> log.e(tag, "Необработанная ошибка в корутине: ${t.message}", t) }
    )
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

    /** FIX: ping сервера не прошёл — соединение мертво, сервис должен переподключиться. */
    private val _reconnectNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnectNeeded: SharedFlow<Unit> = _reconnectNeeded

    /**
     * FIX (SECURITY): bare JID (в нижнем регистре), от которых разрешено принимать команды
     * (restart/update/task/...). null = список не настроен: команды принимаются от любого
     * отправителя (как раньше), в лог пишется предупреждение.
     * TODO: задать из настроек/REST после решения, кто является источником команд.
     */
    @Volatile
    var trustedCommandSenders: Set<String>? = null

    /**
     * FIX: команды, доставленные из офлайн-хранилища Openfire позже этого возраста, не выполняются
     * (иначе «restart», отправленный утром, сработает после подключения вечером).
     * [Твой выбор]: подходящий TTL для твоих команд.
     */
    @Volatile
    var commandMaxAgeMs: Long = 5 * 60_000L

    private val warnedNoTrustList = AtomicBoolean(false)

    private val seenCommandIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 200
    }

    val selfJid: String get() = creds?.let { "${it.username}@${it.domain}" } ?: ""

    val isConnected: Boolean get() = connection?.isAuthenticated == true

    // ------------------------------------------------------------------ connect

    /**
     * Синхронное подключение (connect + login). Исключений наружу не бросает:
     * при ошибке state = Error, при отказе авторизации — событие [authFailed].
     *
     * @param trustAllCerts не проверять цепочку CA. FIX: по умолчанию теперь false (было true).
     *                      В release-сборке сервис передаёт false всегда.
     * @param securityMode  "required" — TLS обязателен (по умолчанию в release);
     *                      "ifpossible" — TLS если сервер предлагает (в debug);
     *                      "disabled" — без TLS, только в debug.
     */
    suspend fun connect(
        c: XmppCredentials,
        trustAllCerts: Boolean = false,
        securityMode: String = if (BuildConfig.DEBUG) "ifpossible" else "required"
    ) {
        withContext(Dispatchers.IO) {
            if (initialized.compareAndSet(false, true)) {
                runCatching { AndroidSmackInitializer.initialize(ctx.applicationContext) }
                    .onFailure { log.e(tag, "AndroidSmackInitializer: ${it.message}") }
            }
            disconnect(silent = true)
            creds = c
            _state.value = XmppState.Connecting
            log.i(tag, "Подключение к ${c.xmppHost}:${c.xmppPort}, домен=${c.domain}, логин=${c.username}, пароль=${log.mask(c.xmppPassword)}")

            var conn: XMPPTCPConnection? = null
            try {
                var mode = when (securityMode) {
                    "disabled" -> ConnectionConfiguration.SecurityMode.disabled
                    "required" -> ConnectionConfiguration.SecurityMode.required
                    else -> ConnectionConfiguration.SecurityMode.ifpossible
                }
                if (mode == ConnectionConfiguration.SecurityMode.disabled && !BuildConfig.DEBUG) {
                    log.e(tag, "SECURITY: securityMode=disabled запрещён в release — используется required")
                    mode = ConnectionConfiguration.SecurityMode.required
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
                    log.w(tag, "Проверка TLS-сертификата отключена (доверие самоподписанному сертификату)")
                    TLSUtils.acceptAllCertificates(configBuilder)
                    TLSUtils.disableHostnameVerificationForTlsCertificates(configBuilder)
                } else {
                    // FIX: доверие корпоративному CA вместо «доверять всем».
                    // Положите сертификат CA в app/src/main/assets/corp_ca.crt.
                    // Имя хоста проверяется по XMPP-домену: в сертификате Openfire он должен быть в SAN.
                    corpSslContext()?.let {
                        configBuilder.setCustomSSLContext(it)
                        log.i(tag, "TLS: используется корпоративный CA из assets/$CORP_CA_ASSET")
                    }
                }

                val newConn = XMPPTCPConnection(configBuilder.build())
                conn = newConn
                newConn.replyTimeout = 10_000L
                connection = newConn

                // Автопереподключение после обрыва установленной сессии (п. 4.3 ТЗ)
                ReconnectionManager.getInstanceFor(newConn).apply {
                    setReconnectionPolicy(ReconnectionManager.ReconnectionPolicy.RANDOM_INCREASING_DELAY)
                    enableAutomaticReconnection()
                }

                newConn.addConnectionListener(listenerFor(newConn))
                attachListeners(newConn)
                setupPing(newConn)

                newConn.connect()
                newConn.login()
            } catch (t: Exception) {
                val msg = t.message ?: t.javaClass.simpleName
                log.e(tag, "Ошибка подключения: $msg", t)
                // FIX: не оставляем полуоткрытое соединение (connect прошёл, login — нет)
                conn?.let { failed ->
                    if (connection === failed) connection = null
                    runCatching { ReconnectionManager.getInstanceFor(failed).disableAutomaticReconnection() }
                    runCatching { failed.disconnect() }
                }
                _state.value = XmppState.Error(msg)
                if (isAuthError(t)) scope.launch { _authFailed.emit(Unit) }
            }
        }
    }

    /** FIX: раньше срабатывало на любую строку с "SASL" -> ложные «ошибки авторизации». */
    private fun isAuthError(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur is SASLErrorException) return true
            if (cur.message?.contains("not-authorized", ignoreCase = true) == true) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun corpSslContext(): SSLContext? {
        val stream = runCatching { ctx.assets.open(CORP_CA_ASSET) }.getOrNull() ?: return null
        return stream.use {
            val ca = CertificateFactory.getInstance("X.509").generateCertificate(it)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("corp", ca)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        }
    }

    fun disconnect(silent: Boolean = false) {
        val conn = connection ?: return
        // FIX: сначала «отвязываем» соединение — события заменяемого соединения игнорируются,
        // а его ReconnectionManager отключается (иначе зомби-сессия воюет с новой за ресурс "wakeup").
        connection = null
        runCatching { ReconnectionManager.getInstanceFor(conn).disableAutomaticReconnection() }
        runCatching {
            if (conn.isConnected) {
                runCatching { conn.sendStanza(presenceStanza(Presence.Type.unavailable)) }
                conn.disconnect()
            }
        }
        if (!silent) {
            log.i(tag, "XMPP-соединение закрыто")
            _state.value = XmppState.Disconnected
            scope.launch { repo.allOffline() }
        }
    }

    // ---------------------------------------------------------------- listeners

    /** FIX: слушатель привязан к конкретному соединению и игнорирует события устаревших. */
    private fun listenerFor(owner: XMPPTCPConnection) = object : ConnectionListener {
        private fun isCurrent() = owner === this@XmppManager.connection

        override fun connected(connection: XMPPConnection) {
            if (!isCurrent()) return
            log.i(tag, "TCP-соединение установлено")
            _state.value = XmppState.Connected
        }

        override fun authenticated(connection: XMPPConnection, resumed: Boolean) {
            if (!isCurrent()) return
            val jid = connection.user?.toString() ?: selfJid
            log.i(tag, "Авторизация успешна: $jid (возобновление=$resumed)")
            _state.value = XmppState.Authenticated(jid)
            scope.launch {
                applyPresenceMode(currentPresenceMode)
                flushOfflineQueue()
            }
        }

        override fun connectionClosed() {
            if (!isCurrent()) return
            log.i(tag, "Соединение закрыто")
            _state.value = XmppState.Disconnected
            scope.launch { repo.allOffline() }
        }

        override fun connectionClosedOnError(e: Exception) {
            if (!isCurrent()) return
            log.e(tag, "Соединение разорвано с ошибкой: ${e.message}")
            _state.value = XmppState.Error(e.message ?: "ошибка соединения")
            scope.launch { repo.allOffline() }
            if (isAuthError(e)) scope.launch { _authFailed.emit(Unit) }
        }
    }

    /**
     * FIX: раньше PingManager не настраивался (по умолчанию ping раз в ~30 минут), и полуоткрытое
     * TCP-соединение (роуминг между точками Wi-Fi, сон Wi-Fi на ТСД) считалось живым десятки минут.
     * [Твой выбор]: интервал — компромисс между скоростью обнаружения обрыва и батареей.
     */
    private fun setupPing(conn: XMPPTCPConnection) {
        PingManager.getInstanceFor(conn).apply {
            pingInterval = PING_INTERVAL_SEC
            registerPingFailedListener(PingFailedListener {
                log.w(tag, "Ping сервера не прошёл — соединение считается мёртвым")
                if (conn === connection) _reconnectNeeded.tryEmit(Unit)
            })
        }
    }

    private fun attachListeners(conn: XMPPTCPConnection) {
        // Входящие сообщения
        ChatManager.getInstanceFor(conn).addIncomingListener { from, message, _ ->
            handleIncoming(from, message)
        }

        // Ростер и статусы собеседников
        val roster = Roster.getInstanceFor(conn)
        // TODO(SECURITY): accept_all принимает подписки от кого угодно. Если ТЗ не требует —
        //  используйте Roster.SubscriptionMode.manual или белый список.
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
        // FIX: маркер «прочитано» приходит без тела — раньше он терялся из-за раннего return
        scope.launch { handleDisplayedMarker(message) }

        val body = message.body ?: return
        val jid = from.asBareJid().toString()
        // FIX: stanzaId может отсутствовать (platform type) -> NPE в repo
        val stanzaId = message.stanzaId ?: UUID.randomUUID().toString()
        val command = CommandParser.parse(body)

        val rejectReason = if (command != null) commandRejectReason(jid, stanzaId, message) else null

        log.i(
            tag,
            "Получено XMPP-сообщение от $jid: " + when {
                command == null -> "текст (${body.length} симв.)"
                rejectReason != null -> "команда ${command.logName} ОТКЛОНЕНА ($rejectReason)"
                else -> "команда ${command.logName}"
            }
        )

        scope.launch {
            repo.ensureUser(jid)
            repo.saveIncoming(stanzaId, jid, body, command != null)
            when {
                command == null -> _texts.emit(TextEvent(jid, jid.substringBefore('@'), body))
                rejectReason == null -> _commands.emit(CommandEvent(command, jid, body))
                // отклонённая команда сохранена в истории, но не выполняется
            }
        }
    }

    /** null = команду можно выполнять. */
    private fun commandRejectReason(jid: String, id: String, message: Message): String? {
        val trusted = trustedCommandSenders
        if (trusted == null) {
            if (!warnedNoTrustList.getAndSet(true)) {
                log.w(tag, "SECURITY: trustedCommandSenders не задан — команды принимаются от любого отправителя")
            }
        } else if (jid.lowercase() !in trusted) {
            return "отправитель не в списке доверенных"
        }

        // Сообщения из офлайн-хранилища Openfire приходят с XEP-0203 <delay/>
        val stampMs = runCatching { DelayInformationManager.getDelayInformation(message)?.stamp?.time }.getOrNull()
        if (stampMs != null && System.currentTimeMillis() - stampMs > commandMaxAgeMs) {
            return "устарела: доставлена из офлайн-хранилища"
        }

        val fresh = synchronized(seenCommandIds) { seenCommandIds.put(id, true) == null }
        if (!fresh) return "дубликат $id"
        return null
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

    /**
     * online — обычное присутствие; invisible — unavailable при живом соединении.
     * TODO: проверить на Openfire. Скорее всего, при unavailable сервер начинает складывать
     *  сообщения на bare JID в офлайн-хранилище, и команды приходят только при следующем
     *  available/логине. Тогда «невидимку» нужно делать иначе или убрать.
     */
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

    private companion object {
        const val CORP_CA_ASSET = "corp_ca.crt"
        const val PING_INTERVAL_SEC = 60
    }
}
