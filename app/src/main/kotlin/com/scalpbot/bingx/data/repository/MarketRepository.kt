package com.scalpbot.bingx.data.repository

import com.scalpbot.bingx.data.local.db.dao.PairDao
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import com.scalpbot.bingx.data.remote.bingx.BingXRestClient
import com.scalpbot.bingx.data.remote.bingx.BingXWebSocketClient
import com.scalpbot.bingx.data.remote.bingx.dto.MarketWsEvent
import com.scalpbot.bingx.domain.model.Candle
import com.scalpbot.bingx.domain.model.PairTicker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val SPARKLINE_LENGTH = 30

/**
 * Міст між кешем пар у Room, REST (початкові ціни/свічки) і живим WS —
 * використовується екраном Market. Той самий BingXWebSocketClient, що й
 * TradingEngine, тож відкриття екрана не створює другого з'єднання.
 */
class MarketRepository(
    private val bingXRestClient: BingXRestClient,
    private val bingXWebSocketClient: BingXWebSocketClient,
    private val pairDao: PairDao,
) {
    private val _pairTickers = MutableStateFlow<List<PairTicker>>(emptyList())
    val pairTickers: StateFlow<List<PairTicker>> = _pairTickers

    private val livePrices = ConcurrentHashMap<String, Double>()
    private val liveChangePercent = ConcurrentHashMap<String, Double>()
    private val sparklines = ConcurrentHashMap<String, MutableList<Double>>()

    @Volatile private var cachedPairs: List<PairCacheEntity> = emptyList()

    fun start(scope: CoroutineScope) {
        scope.launch {
            pairDao.observeAll().collect { pairs ->
                cachedPairs = pairs
                recompute()
            }
        }
        scope.launch {
            val symbols = pairDao.getEnabledSymbols()
            seedInitialPrices()
            seedSparklines(symbols)
            recompute()
            bingXWebSocketClient.start(scope, symbols)
        }
        scope.launch {
            bingXWebSocketClient.events.collect { event ->
                when (event) {
                    is MarketWsEvent.TickerUpdate -> {
                        livePrices[event.symbol] = event.lastPrice
                        liveChangePercent[event.symbol] = event.priceChangePercent
                        recompute()
                    }
                    is MarketWsEvent.KlineUpdate -> if (event.interval.contains("1min")) {
                        val list = sparklines.getOrPut(event.symbol) { mutableListOf() }
                        list.add(event.kline.close)
                        while (list.size > SPARKLINE_LENGTH) list.removeAt(0)
                        livePrices[event.symbol] = event.kline.close
                        recompute()
                    }
                    else -> Unit
                }
            }
        }
    }

    suspend fun setPairEnabled(symbol: String, enabled: Boolean) {
        pairDao.setEnabled(symbol, enabled)
    }

    suspend fun getKlinesForChart(symbol: String, interval: String, limit: Int = 100): Result<List<Candle>> =
        bingXRestClient.getKlines(symbol, interval, limit).map { klines ->
            klines.map { Candle(it.openTimeMs, it.open, it.high, it.low, it.close, it.volume) }
        }

    private suspend fun seedInitialPrices() {
        val tickers = bingXRestClient.getAllTickers24h().getOrNull().orEmpty()
        tickers.forEach {
            livePrices[it.symbol] = it.lastPrice
            liveChangePercent[it.symbol] = it.priceChangePercent
        }
    }

    private suspend fun seedSparklines(symbols: List<String>) = coroutineScope {
        symbols.map { symbol ->
            async {
                val klines = bingXRestClient.getKlines(symbol, "1min", SPARKLINE_LENGTH).getOrNull().orEmpty()
                if (klines.isNotEmpty()) sparklines[symbol] = klines.map { it.close }.toMutableList()
            }
        }.awaitAll()
    }

    private fun recompute() {
        _pairTickers.value = cachedPairs.map { pair ->
            PairTicker(
                symbol = pair.symbol,
                baseAsset = pair.baseAsset,
                lastPrice = livePrices[pair.symbol] ?: 0.0,
                priceChangePercent24h = liveChangePercent[pair.symbol] ?: pair.priceChangePercent24h,
                volume24h = pair.volume24h,
                sparkline = sparklines[pair.symbol]?.toList().orEmpty(),
                enabled = pair.enabled,
            )
        }
    }
}
