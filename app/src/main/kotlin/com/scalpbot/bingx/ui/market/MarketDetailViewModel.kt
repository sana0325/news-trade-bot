package com.scalpbot.bingx.ui.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.domain.model.Candle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MarketDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ScalpBotApp).serviceLocator

    private val _candles = MutableStateFlow<List<Candle>>(emptyList())
    val candles: StateFlow<List<Candle>> = _candles

    private val _trades = MutableStateFlow<List<TradeEntity>>(emptyList())
    val trades: StateFlow<List<TradeEntity>> = _trades

    private var loadedSymbol: String? = null

    fun load(symbol: String) {
        if (loadedSymbol == symbol) return
        loadedSymbol = symbol
        viewModelScope.launch {
            _candles.value = locator.marketRepository.getKlinesForChart(symbol, "5min", 100)
            _trades.value = locator.database.tradeDao().getSince(0L).filter { it.symbol == symbol }
        }
    }
}
