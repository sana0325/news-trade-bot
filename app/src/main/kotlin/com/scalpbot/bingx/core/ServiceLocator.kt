package com.scalpbot.bingx.core

import android.content.Context
import com.scalpbot.bingx.data.local.db.AppDatabase
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import com.scalpbot.bingx.data.remote.NetworkClientFactory
import com.scalpbot.bingx.data.remote.bingx.BingXRestClient
import com.scalpbot.bingx.data.remote.bingx.BingXWebSocketClient
import com.scalpbot.bingx.data.remote.deepseek.DeepSeekClient
import com.scalpbot.bingx.data.repository.MarketRepository
import com.scalpbot.bingx.domain.engine.RiskManager
import com.scalpbot.bingx.domain.engine.SingleTradeLock
import com.scalpbot.bingx.domain.engine.TradingEngine
import com.scalpbot.bingx.notification.TradeNotifier

/**
 * Ручний DI-контейнер. Проєкт одноосібний і не настільки великий, щоб
 * виправдати Hilt/Dagger — явний контейнер простіше збирати в CI й читати.
 */
class ServiceLocator private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }
    val secureConfigStore: SecureConfigStore by lazy { SecureConfigStore(appContext) }

    private val sharedHttpClient by lazy { NetworkClientFactory.create() }
    val bingXRestClient: BingXRestClient by lazy { BingXRestClient(sharedHttpClient, secureConfigStore) }
    val bingXWebSocketClient: BingXWebSocketClient by lazy { BingXWebSocketClient(sharedHttpClient) }
    val deepSeekClient: DeepSeekClient by lazy { DeepSeekClient(sharedHttpClient, secureConfigStore) }

    val riskManager: RiskManager by lazy { RiskManager(secureConfigStore, database.tradeDao()) }
    val tradeNotifier: TradeNotifier by lazy { TradeNotifier(appContext) }

    val marketRepository: MarketRepository by lazy {
        MarketRepository(bingXRestClient, bingXWebSocketClient, database.pairDao())
    }

    /** Один інстанс на процес — FGS і UI (для стану) використовують той самий двигун. */
    val tradingEngine: TradingEngine by lazy {
        TradingEngine(
            bingXRestClient = bingXRestClient,
            bingXWebSocketClient = bingXWebSocketClient,
            deepSeekClient = deepSeekClient,
            secureConfigStore = secureConfigStore,
            tradeDao = database.tradeDao(),
            pairDao = database.pairDao(),
            lessonDao = database.lessonDao(),
            riskManager = riskManager,
            singleTradeLock = SingleTradeLock(),
        )
    }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun getInstance(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
