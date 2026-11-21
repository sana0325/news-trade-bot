package com.ndscan.bot

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        BotService.createChannels(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build())
    }
}

/** Автостарт після перезавантаження / оновлення, якщо бот був увімкнений. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Settings.isEnabled(ctx)) tryStart(ctx, BotService.ACTION_START)
    }
}

/** Кожні 15 хв: сервіс мертвий або Python мовчить > 5 хв — перезапуск. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Settings.isEnabled(ctx)) return Result.success()
        val hb = Settings.runtime(ctx).getLong("heartbeat", 0)
        val stale = System.currentTimeMillis() - hb > 5 * 60_000
        when {
            !BotService.running -> tryStart(ctx, BotService.ACTION_START)
            stale -> tryStart(ctx, BotService.ACTION_RESTART)
        }
        return Result.success()
    }
}

/** Android 12+ може заборонити старт foreground-сервісу з фону — тоді просимо відкрити застосунок. */
fun tryStart(ctx: Context, action: String) {
    try {
        BotService.start(ctx, action)
    } catch (e: Exception) {
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, BotService.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ndscan не зміг перезапуститися")
            .setContentText("Відкрийте застосунок і дайте виняток з оптимізації батареї")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(2, n)
    }
}
