package com.scalpbot.bingx.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

// Повна реалізація (нотифікація, wake lock, TradingEngine-цикл) — фаза 6.
class TradingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
