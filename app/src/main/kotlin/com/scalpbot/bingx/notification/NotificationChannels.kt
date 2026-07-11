package com.scalpbot.bingx.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Три окремих канали, як вимагає ТЗ: угоди / системні / сервіс. Усі працюють на заблокованому екрані. */
object NotificationChannels {
    const val TRADES = "scalpbot_trades"
    const val SYSTEM = "scalpbot_system"
    const val SERVICE = "scalpbot_service"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(TRADES, "Угоди", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Відкриття та закриття угод бота"
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SYSTEM, "Системні сповіщення", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Kill-switch, денний ліміт, обрив зв'язку, готові звіти"
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "Статус сервісу", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постійна нотифікація про роботу торгового двигуна"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }
}
