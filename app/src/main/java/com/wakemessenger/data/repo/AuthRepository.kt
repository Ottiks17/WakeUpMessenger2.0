package com.wakemessenger.data.repo

import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.remote.ApiClient
import com.wakemessenger.data.remote.CredentialsStore
import com.wakemessenger.data.remote.XmppCredentials
import kotlinx.coroutines.delay

/**
 * Получение учётных данных (п. 4.3 ТЗ):
 * при недоступности REST API — повтор через 10 сек, максимум 5 раз,
 * затем fallback на последние сохранённые креды.
 */
class AuthRepository(
    private val api: ApiClient,
    private val store: CredentialsStore,
    private val settings: SettingsRepository,
    private val log: FileLogger
) {
    private val tag = "AUTH"

    sealed interface Result {
        data class Fresh(val creds: XmppCredentials) : Result
        data class Fallback(val creds: XmppCredentials) : Result
        data object None : Result
    }

    suspend fun obtainCredentials(): Result {
        val deviceId = settings.deviceId()
        repeat(Const.CREDS_MAX_ATTEMPTS) { attempt ->
            val n = attempt + 1
            try {
                log.i(tag, "Запрос учётных данных, попытка $n из ${Const.CREDS_MAX_ATTEMPTS} (deviceId=$deviceId)")
                val creds = api.fetchCredentials(deviceId)
                store.save(creds)
                // Сервер может указать рабочий адрес API — сохраняем его.
                if (creds.apiHost.isNotBlank()) {
                    settings.set(Const.S_API_HOST, creds.apiHost)
                    settings.set(Const.S_API_PORT, creds.apiPort.toString())
                }
                log.i(tag, "Учётные данные получены успешно")
                return Result.Fresh(creds)
            } catch (t: Throwable) {
                log.e(tag, "Не удалось получить учётные данные (попытка $n): ${t.message}")
                if (n < Const.CREDS_MAX_ATTEMPTS) delay(Const.CREDS_RETRY_DELAY_MS)
            }
        }
        val last = store.last()
        return if (last != null) {
            log.w(tag, "REST API недоступен: используем последние сохранённые учётные данные")
            Result.Fallback(last)
        } else {
            log.e(tag, "REST API недоступен и сохранённых учётных данных нет")
            Result.None
        }
    }

    fun invalidate() = store.clear()
}
