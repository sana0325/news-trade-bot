package com.scalpbot.bingx.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scalpbot.bingx.R
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.core.ServiceLocator
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.domain.model.EngineState
import com.scalpbot.bingx.domain.model.EngineStatus
import com.scalpbot.bingx.notification.NotificationChannels
import com.scalpbot.bingx.service.worker.ServiceWatchdogWorker
import com.scalpbot.bingx.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "TradingForegroundService"
private const val NOTIFICATION_ID = 1001

/**
 * Торгове ядро живе тут, у specialUse Foreground Service з partial wake lock —
 * торгівля триває при заблокованому екрані й закритому UI. START_STICKY +
 * ServiceWatchdogWorker (WorkManager, 15 хв) — страховка, якщо систему все ж
 * уб'є процес. Позиції в цей час захищені: SL/TP завжди стоять на біржі.
 */
class TradingForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.scalpbot.bingx.action.START"
        const val ACTION_PAUSE = "com.scalpbot.bingx.action.PAUSE"
        const val ACTION_RESUME = "com.scalpbot.bingx.action.RESUME"
        const val ACTION_STOP = "com.scalpbot.bingx.action.STOP"
        const val ACTION_CLOSE_ALL = "com.scalpbot.bingx.action.CLOSE_ALL"

        fun intent(context: Context, action: String) =
            Intent(context, TradingForegroundService::class.java).setAction(action)
    }

    inner class LocalBinder : Binder() {
        val serviceLocator: ServiceLocator get() = locator
    }

    private val binder = LocalBinder()
    private lateinit var locator: ServiceLocator
    private lateinit var serviceScope: CoroutineScope
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locator = (application as ScalpBotApp).serviceLocator
        locator.tradingEngine.notifier = locator.tradeNotifier
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        acquireWakeLock()
        networkMonitor = NetworkMonitor(this) {
            serviceScope.launch {
                locator.bingXWebSocketClient.stop()
                val symbols = locator.database.pairDao().getEnabledSymbols()
                locator.bingXWebSocketClient.start(serviceScope, symbols)
            }
        }.also { it.register() }

        startForeground(NOTIFICATION_ID, buildNotification(locator.tradingEngine.state.value))
        observeEngineState()
        ServiceWatchdogWorker.schedule(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> locator.tradingEngine.pause()
            ACTION_RESUME -> locator.tradingEngine.resume()
            ACTION_STOP -> {
                locator.tradingEngine.stop()
                ServiceWatchdogWorker.cancel(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CLOSE_ALL -> serviceScope.launch {
                runCatching { locator.tradingEngine.closeAllNow(CloseReason.MANUAL) }
                    .onFailure { AppLogger.e(TAG, "Закрити все: помилка", it) }
            }
            else -> locator.tradingEngine.start(serviceScope) // ACTION_START, watchdog-пінг, автозапуск після boot
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        networkMonitor?.unregister()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeEngineState() {
        stateObserverJob = serviceScope.launch {
            locator.tradingEngine.state.collect { state ->
                updateNotification(state)
            }
        }
    }

    private fun updateNotification(state: EngineState) {
        val notification = buildNotification(state)
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
            .onFailure { AppLogger.w(TAG, "Не вдалось оновити нотифікацію сервісу", it) }
    }

    private fun buildNotification(state: EngineState): Notification {
        val statusText = when (state.status) {
            EngineStatus.RUNNING -> "Онлайн · ${state.mode}"
            EngineStatus.PAUSED -> "Пауза · ${state.mode}"
            EngineStatus.KILL_SWITCHED -> "Kill-switch активовано"
            EngineStatus.STOPPED -> "Зупинено"
        }
        val positions = if (state.openPositionSymbol != null) "1/1 (${state.openPositionSymbol})" else "0/1"
        val pnl = String.format(java.util.Locale.US, "%.2f", state.pnlTodayUsd)
        val content = "Позицій: $positions · PnL сьогодні: $pnl$"

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(statusText)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)

        if (state.status == EngineStatus.RUNNING) {
            builder.addAction(0, "Пауза", servicePendingIntent(ACTION_PAUSE))
        } else if (state.status == EngineStatus.PAUSED) {
            builder.addAction(0, "Продовжити", servicePendingIntent(ACTION_RESUME))
        }
        builder.addAction(0, "Стоп", servicePendingIntent(ACTION_STOP))

        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(), intent(this, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScalpBot::TradingWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // safety timeout 12 год, знімається onDestroy
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
