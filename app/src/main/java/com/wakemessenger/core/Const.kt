
package com.wakemessenger.core

object Const {
    const val TAG = "WakeUp"

    // --- Уведомления ---
    const val CH_SERVICE = "wakeup_service"
    const val CH_MESSAGES = "wakeup_messages"
    const val CH_COMMANDS = "wakeup_commands"
    // Каналы по приоритету сообщения (importance канала после создания менять нельзя,
    // поэтому это новые ID; старый CH_MESSAGES удаляется в NotificationHelper).
    const val CH_MSG_LOW = "wakeup_msg_low"
    const val CH_MSG_NORMAL = "wakeup_msg_normal"
    const val CH_MSG_HIGH = "wakeup_msg_high"
    const val CH_MSG_CRITICAL = "wakeup_msg_critical"
    const val NOTIF_SERVICE_ID = 1001
    const val NOTIF_MESSAGE_BASE = 2000
    const val NOTIF_COMMAND_BASE = 3000

    // --- HTTP (п. 5.2 ТЗ) ---
    const val HTTP_TIMEOUT_SEC = 3L

    // --- Повторы (п. 4.3 ТЗ) ---
    const val CREDS_RETRY_DELAY_MS = 10_000L
    const val CREDS_MAX_ATTEMPTS = 5

    // --- WakeLock (п. 3.1 ТЗ) ---
    const val WAKELOCK_TAG = "wakemessenger:http"
    const val WAKELOCK_MAX_MS = 5_000L

    // --- Логирование (п. 5.3 ТЗ) ---
    const val LOG_FILE = "log.txt"
    const val LOG_FILE_OLD = "log.old.txt"
    const val LOG_MAX_BYTES = 10L * 1024 * 1024

    // --- Ключи настроек (таблица chat_settings) ---
    const val S_API_HOST = "api_host"
    const val S_API_PORT = "api_port"
    const val S_DEVICE_ID = "device_id"
    const val S_PRESENCE = "presence_mode"      // online | invisible
    const val S_TRUSTED_SENDERS = "trusted_senders"  // bare JID через запятую; кэш ответа REST

    // --- Режим получения XMPP-кредов (независимый блок "чат") ---
    const val S_XMPP_MODE = "xmpp_mode"          // auto | manual
    const val S_XMPP_MANUAL_HOST = "xmpp_manual_host"
    const val S_XMPP_MANUAL_PORT = "xmpp_manual_port"
    const val S_XMPP_MANUAL_LOGIN = "xmpp_manual_login"   // user или user@domain
    const val MODE_AUTO = "auto"     // получать креды через REST API (п. 4.1 ТЗ)
    const val MODE_MANUAL = "manual" // вводятся руками, REST API не вызывается вообще

    // Доверие TLS-сертификату Openfire без проверки цепочки CA — актуально для
    // внутренних сетей склада, где сервер использует самоподписанный сертификат.
    const val S_XMPP_TRUST_ALL_CERTS = "xmpp_trust_all_certs"

    // --- Приоритет сообщений (stage1_contract.md) ---
    const val PRIORITY_ELEMENT = "priority"
    const val PRIORITY_NS = "urn:wakeup:msg:0"
    // GET {api}/v1/config/trusted-senders?deviceId=... -> {"senders":["server@domain", ...]}
    const val TRUSTED_SENDERS_PATH = "/v1/config/trusted-senders"

    // --- Значения по умолчанию ---
    const val DEF_API_HOST = "192.168.0.100"
    const val DEF_API_PORT = "80"
    // WMS-приложение: пакет и action больше не настраиваются в UI — меняются только здесь.
    // ВАЖНО: WMS_PACKAGE должен совпадать с реальным пакетом WMS на ТСД, а WMS должно
    // обрабатывать WMS_ACTION (иначе используется запуск через launcher-Intent, п. 13 ТЗ).
    const val WMS_PACKAGE = "ru.wms.client"
    const val WMS_ACTION = "com.wakemessenger.OPEN_TASK"

    // --- Intent в WMS (п. 2.3 ТЗ) ---
    const val EXTRA_TASK_ID = "task_id"

    const val PRESENCE_ONLINE = "online"
    const val PRESENCE_INVISIBLE = "invisible"

    // --- Действия сервиса ---
    const val ACTION_START = "com.wakemessenger.action.START"
    const val ACTION_STOP = "com.wakemessenger.action.STOP"
    const val ACTION_RECONNECT = "com.wakemessenger.action.RECONNECT"
}


