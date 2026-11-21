package com.ndscan.bot

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground-сервіс: тримає Python-потік з ndscan.scan() 24/7.
 * PARTIAL_WAKE_LOCK + WifiLock на весь час роботи, START_STICKY, перезапуск після змаху з недавніх.
 */
class BotService : Service() {

    companion object {
        const val CHANNEL = "bot"
        const val CHANNEL_ALERT = "alert"
        const val NOTIF_ID = 1
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RESTART = "restart"     // від watchdog: перезапустити Python-потік
        private const val TAG = "BotService"
        private const val POLL_MS = 15_000L

        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, BotService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            Settings.setEnabled(ctx, false)
            if (running) ctx.startService(Intent(ctx, BotService::class.java).setAction(ACTION_STOP))
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Робота бота", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Попередження", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pyThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    // Опитування Python окремо від UI: поки Python тримає GIL, головний потік не блокується.
    private val pollThread = HandlerThread("ndscan-poll").apply { start() }
    private val handler = Handler(pollThread.looper)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val runner by lazy { Python.getInstance().getModule("runner") }

    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        goForeground()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> { stopBot(); return START_NOT_STICKY }
            ACTION_RESTART -> restartPython("watchdog")
            else -> {
                // null intent = система перезапустила сервіс після START_STICKY.
                if (intent == null && !Settings.isEnabled(this)) { stopSelf(); return START_NOT_STICKY }
                Settings.setEnabled(this, true)
                startBot()
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(BotState.status.value), type)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ndscan:bot").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ndscan:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null
    }

    @Synchronized
    private fun startBot() {
        if (pyThread?.isAlive == true) return
        acquireLocks()
        Settings.seed(this)
        Settings.writeBotFiles(this)
        BotState.update { it.copy(state = "starting", started = System.currentTimeMillis()) }
        Settings.runtime(this).edit().putLong("heartbeat", System.currentTimeMillis()).apply()
        val dir = filesDir.absolutePath
        val env = Settings.envJson(this)
        pyThread = Thread({
            try {
                runner.callAttr("run", dir, env, LogSink())
            } catch (t: Throwable) {
                Log.e(TAG, "python", t)
                BotState.addLog("${timeFmt.format(Date())} Python-потік завершився: $t")
            }
        }, "ndscan-python").apply { start() }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** Зупинити Python і дочекатися виходу з циклу (save_state), у фоновому потоці. */
    private fun stopPython(then: () -> Unit) {
        val t = pyThread
        BotState.update { it.copy(state = "stopping") }
        Thread {
            try { runner.callAttr("stop") } catch (e: Throwable) { Log.e(TAG, "stop", e) }
            t?.join(60_000)
            main.post(then)
        }.start()
    }

    private fun restartPython(why: String) {
        BotState.addLog("${timeFmt.format(Date())} Перезапуск Python-потоку ($why)")
        if (pyThread?.isAlive != true) { startBot(); return }
        stopPython {
            pyThread = null
            if (Settings.isEnabled(this)) startBot()
        }
    }

    private fun stopBot() {
        Settings.setEnabled(this, false)
        handler.removeCallbacks(poll)
        stopPython {
            pyThread = null
            releaseLocks()
            BotState.update { it.copy(state = "stopped") }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun refreshStatus() {
        try {
            val s = Status.fromJson(runner.callAttr("status").toString(), BotState.status.value)
            BotState.setStatus(s)
            if (s.heartbeat > 0) Settings.runtime(this).edit().putLong("heartbeat", s.heartbeat).apply()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
        } catch (e: Throwable) {
            Log.e(TAG, "status", e)
        }
    }

    private fun buildNotification(s: Status): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = "ndscan: " + stateLabel(s.state)
        val loop = if (s.loopTs > 0) timeFmt.format(Date(s.loopTs)) else "—"
        val text = "Блок ${s.block ?: "—"} · сигналів за 6г: ${s.signals6h} · цикл $loop"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$text\nВідставання ${s.lag ?: "—"} · у слежці ${s.watch} · депозитів ${s.deposits} · угод ${s.trades}"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Змахнули з недавніх: якщо бот увімкнений — підняти сервіс знову. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.isEnabled(this)) scheduleRestart(1_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        pollThread.quitSafely()
        releaseLocks()
        running = false
        if (Settings.isEnabled(this)) scheduleRestart(5_000)
        super.onDestroy()
    }

    private fun scheduleRestart(delayMs: Long) {
        val pi = PendingIntent.getForegroundService(this, 1,
            Intent(this, BotService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(AlarmManager::class.java)
            .set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    }
}

fun stateLabel(s: String) = when (s) {
    "running" -> "працює"
    "starting" -> "запуск…"
    "restarting" -> "перезапуск"
    "stopping" -> "зупиняється…"
    else -> "зупинено"
}
