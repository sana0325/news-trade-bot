package com.scalpbot.bingx

import android.app.Application
import com.scalpbot.bingx.core.ServiceLocator

class ScalpBotApp : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator.getInstance(this)
    }
}
