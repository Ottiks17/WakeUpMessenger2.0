package com.wakemessenger.data.remote

import android.content.Context
import android.os.PowerManager
import com.wakemessenger.core.Const
import com.wakemessenger.core.FileLogger
import com.wakemessenger.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP-клиент (п. 5.2 ТЗ).
 * Все запросы асинхронные (OkHttp.enqueue) и не блокируют XMPP-поток.
 * Таймауты connect/read/write = 3 сек.
 * PARTIAL_WAKE_LOCK удерживается только на время запроса, максимум 5 сек (п. 3.1 ТЗ).
 */
class ApiClient(
    private val ctx: Context,
    private val log: FileLogger,
    private val settings: SettingsRepository
) {
    private val tag = "HTTP"

    private val client = OkHttpClient.Builder()
        .connectTimeout(Const.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(Const.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(Const.HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val powerManager by lazy { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }

    private val _online = MutableStateFlow<Boolean?>(null)
    /** null — запросов ещё не было; true/false — результат последнего обращения к REST API. */
    val online: StateFlow<Boolean?> = _online

    private data class HttpResult(val code: Int, val body: String)

    private suspend fun execute(request: Request): HttpResult =
        suspendCancellableCoroutine { cont ->
            val wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Const.WAKELOCK_TAG)
            val released = AtomicBoolean(false)
            fun release() {
                if (released.compareAndSet(false, true)) {
                    runCatching { if (wl.isHeld) wl.release() }
                }
            }
            runCatching { wl.acquire(Const.WAKELOCK_MAX_MS) }

            val started = System.currentTimeMillis()
            val call = client.newCall(request)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
                release()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    release()
                    _online.value = false
                    log.e(tag, "${request.method} ${request.url} -> ОШИБКА за ${System.currentTimeMillis() - started} мс: ${e.message}")
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = runCatching { response.body?.string().orEmpty() }.getOrElse { "" }
                    response.close()
                    release()
                    _online.value = response.isSuccessful
                    log.i(tag, "${request.method} ${request.url} -> ${response.code} за ${System.currentTimeMillis() - started} мс")
                    if (cont.isActive) cont.resume(HttpResult(response.code, body))
                }
            })
        }

    // ---------- GET /v1/auth/xmpp ----------

    suspend fun fetchCredentials(deviceId: String): XmppCredentials {
        val url = "${settings.apiBaseUrl()}/v1/auth/xmpp?deviceId=${enc(deviceId)}"
        val res = execute(Request.Builder().url(url).get().build())
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        val j = JSONObject(res.body)
        val creds = XmppCredentials(
            xmppHost = j.getString("xmppHost"),
            xmppPort = j.optInt("xmppPort", 5222),
            xmppLogin = j.getString("xmppLogin"),
            xmppPassword = j.getString("xmppPassword"),
            apiHost = j.optString("apiHost", ""),
            apiPort = j.optInt("apiPort", 80)
        )
        log.i(
            tag,
            "Получены учётные данные: host=${creds.xmppHost}:${creds.xmppPort}, login=${creds.xmppLogin}, password=${log.mask(creds.xmppPassword)}"
        )
        return creds
    }

    // ---------- POST /v1/wakeup/confirm ----------

    /**
     * Подтверждение пробуждения. Ошибка не блокирует запуск WMS (п. 4.3 ТЗ) —
     * вызывающий код только логирует результат.
     */
    suspend fun confirmWakeup(deviceId: String, taskId: String): Boolean {
        val url = "${settings.apiBaseUrl()}/v1/wakeup/confirm?deviceId=${enc(deviceId)}&taskId=${enc(taskId)}"
        val json = JSONObject()
            .put("deviceId", deviceId)
            .put("taskId", taskId)
            .toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val res = execute(Request.Builder().url(url).post(body).build())
        return res.code in 200..299
    }

    // ---------- GET /v1/tasks ----------

    suspend fun tasks(deviceId: String): List<TaskItem> {
        val url = "${settings.apiBaseUrl()}/v1/tasks?deviceId=${enc(deviceId)}"
        val res = execute(Request.Builder().url(url).get().build())
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        val arr = runCatching { JSONArray(res.body) }.getOrElse {
            JSONObject(res.body).optJSONArray("tasks") ?: JSONArray()
        }
        return (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            TaskItem(
                taskId = o.optString("taskId", o.optString("id", "")),
                title = o.optString("title", o.optString("name", "")),
                raw = o.toString()
            )
        }
    }

    // ---------- Диагностика счётчика (п. 9.4 ТЗ) ----------

    suspend fun wakeupInfo(deviceId: String): WakeupInfo {
        val url = "${settings.apiBaseUrl()}/wakeup/info?device=${enc(deviceId)}"
        val res = execute(Request.Builder().url(url).get().build())
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        val j = JSONObject(res.body)
        return WakeupInfo(j.optInt("pingCount", -1), j.optInt("oldPingCount", -1), res.body)
    }

    suspend fun wakeupReset(deviceId: String): String {
        val url = "${settings.apiBaseUrl()}/wakeup/reset?device=${enc(deviceId)}"
        val res = execute(Request.Builder().url(url).delete().build())
        if (res.code !in 200..299) throw IOException("HTTP ${res.code}")
        return res.body
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
