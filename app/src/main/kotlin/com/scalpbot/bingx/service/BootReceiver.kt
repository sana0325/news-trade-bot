package com.scalpbot.bingx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.service.worker.ServiceWatchdogWorker

/** Автозапуск сервісу після перезавантаження телефона, якщо бот був активний. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as ShiScalpBotApp
        if (!app.serviceLocator.secureConfigStore.botActive) return

        ContextCompat.startForegroundService(context, Intent(context, TradingForegroundService::class.java))
        ServiceWatchdogWorker.schedule(context)
    }
}
