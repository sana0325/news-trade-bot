package com.scalpbot.bingx

import android.app.Application
import com.scalpbot.bingx.core.ServiceLocator
import com.scalpbot.bingx.notification.NotificationChannels
import com.scalpbot.bingx.service.worker.PairsRefreshWorker

class ScalpBotApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator.getInstance(this)
        NotificationChannels.createAll(this)
        PairsRefreshWorker.runOnce(this)
        PairsRefreshWorker.schedulePeriodic(this)
    }
}
