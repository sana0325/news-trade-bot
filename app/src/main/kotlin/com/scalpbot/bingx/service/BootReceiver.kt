package com.scalpbot.bingx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Автозапуск сервісу після перезавантаження, якщо бот був активний — фаза 6.
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    }
}
