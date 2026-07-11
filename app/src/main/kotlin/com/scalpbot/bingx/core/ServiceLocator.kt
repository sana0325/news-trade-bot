package com.scalpbot.bingx.core

import android.content.Context
import com.scalpbot.bingx.data.local.db.AppDatabase
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore

/**
 * Ручний DI-контейнер. Проєкт одноосібний і не настільки великий, щоб
 * виправдати Hilt/Dagger — явний контейнер простіше збирати в CI й читати.
 */
class ServiceLocator private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }
    val secureConfigStore: SecureConfigStore by lazy { SecureConfigStore(appContext) }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun getInstance(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
