package com.scalpbot.bingx.service.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.service.TradingForegroundService
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "scalpbot_service_watchdog"

/**
 * Страховка на випадок, якщо систему все ж уб'є процес попри START_STICKY:
 * кожні 15 хв (мінімальний інтервал WorkManager) перевіряємо прапорець
 * "бот активний" і, якщо так, (пере)запускаємо сервіс. Позиції в цей час
 * захищені незалежно від цього — SL/TP завжди стоять на біржі.
 */
class ServiceWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ScalpBotApp
        if (app.serviceLocator.secureConfigStore.botActive) {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, TradingForegroundService::class.java),
            )
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
