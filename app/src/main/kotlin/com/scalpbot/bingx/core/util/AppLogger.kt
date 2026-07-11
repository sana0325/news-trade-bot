package com.scalpbot.bingx.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val MAX_LOG_FILE_BYTES = 1_500_000L
private const val LOG_FILE_NAME = "scalpbot.log"
private const val LOG_FILE_BACKUP_NAME = "scalpbot.log.1"

/**
 * Єдина точка логування: logcat завжди, файл з ротацією — після init(context)
 * (викликається в ShiScalpBotApp.onCreate). Один backup-файл при перевищенні
 * ліміту розміру — простий, але робочий варіант ротації для мобільного бота.
 */
object AppLogger {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null
    @Volatile private var backupFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, LOG_FILE_NAME)
        backupFile = File(dir, LOG_FILE_BACKUP_NAME)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeToFile("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeToFile("I", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        writeToFile("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        writeToFile("E", tag, message, throwable)
    }

    /** Останні `maxLines` рядків файлового логу — для екрана перегляду логів у Налаштуваннях. */
    fun readTail(maxLines: Int = 500): String {
        val file = logFile ?: return "Логер ще не ініціалізовано"
        if (!file.exists()) return "Логів поки немає"
        val lines = file.readLines()
        return lines.takeLast(maxLines).joinToString("\n")
    }

    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        ioExecutor.execute {
            try {
                rotateIfNeeded(file)
                val timestamp = timestampFormat.format(Date())
                val throwableText = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
                file.appendText("$timestamp $level/$tag: $message$throwableText\n")
            } catch (_: Exception) {
                // Логування не має валити додаток, навіть якщо диск недоступний.
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
            val backup = backupFile ?: return
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
}
