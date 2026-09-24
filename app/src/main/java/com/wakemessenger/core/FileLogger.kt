package com.wakemessenger.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый логгер (п. 5.3 ТЗ).
 * Путь: /sdcard/Android/data/com.wakemessenger/files/log.txt
 * Ротация: при достижении 10 МБ -> log.old.txt, создаётся новый файл.
 * Пароль XMPP никогда не пишется в открытом виде — используйте [mask].
 */
class FileLogger(private val ctx: Context) {

    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val dir: File
        get() = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    val logFile: File get() = File(dir, Const.LOG_FILE)
    val oldLogFile: File get() = File(dir, Const.LOG_FILE_OLD)

    fun i(tag: String, msg: String) = write("INFO", tag, msg, null)
    fun w(tag: String, msg: String) = write("WARN", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("ERROR", tag, msg, t)

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.format(Date()))
            append(" [").append(level).append("] ")
            append(tag).append(": ").append(msg)
            if (t != null) {
                append('\n')
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
            append('\n')
        }
        when (level) {
            "ERROR" -> Log.e(Const.TAG, "$tag: $msg", t)
            "WARN" -> Log.w(Const.TAG, "$tag: $msg")
            else -> Log.i(Const.TAG, "$tag: $msg")
        }
        runCatching {
            synchronized(lock) {
                val f = logFile
                if (!f.parentFile!!.exists()) f.parentFile!!.mkdirs()
                rotateIfNeeded(f)
                f.appendText(line)
            }
        }
    }

    private fun rotateIfNeeded(f: File) {
        if (f.exists() && f.length() >= Const.LOG_MAX_BYTES) {
            val old = oldLogFile
            if (old.exists()) old.delete()
            if (!f.renameTo(old)) f.delete()
        }
    }

    /** Маскирование пароля — в лог попадает только длина. */
    fun mask(secret: String?): String =
        if (secret.isNullOrEmpty()) "<пусто>" else "***(${secret.length})"

    fun readLog(maxChars: Int = 200_000): String {
        val f = logFile
        if (!f.exists()) return "Лог пуст"
        val text = runCatching { f.readText() }.getOrElse { return "Ошибка чтения лога: ${it.message}" }
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clear() {
        synchronized(lock) {
            runCatching { logFile.delete() }
            runCatching { oldLogFile.delete() }
        }
    }
}
