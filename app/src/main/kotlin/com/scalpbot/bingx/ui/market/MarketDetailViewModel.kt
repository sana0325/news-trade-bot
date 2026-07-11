package com.scalpbot.bingx.ui.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.domain.model.Candle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "MarketDetailViewModel"

sealed interface ChartLoadState {
    data object Loading : ChartLoadState
    data class Loaded(val candles: List<Candle>) : ChartLoadState
    data class Error(val message: String) : ChartLoadState
}

class MarketDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ScalpBotApp).serviceLocator

    private val _chartState = MutableStateFlow<ChartLoadState>(ChartLoadState.Loading)
    val chartState: StateFlow<ChartLoadState> = _chartState

    private val _trades = MutableStateFlow<List<TradeEntity>>(emptyList())
    val trades: StateFlow<List<TradeEntity>> = _trades

    private var loadedSymbol: String? = null

    fun load(symbol: String) {
        if (loadedSymbol == symbol && _chartState.value !is ChartLoadState.Error) return
        loadedSymbol = symbol
        _chartState.value = ChartLoadState.Loading
        viewModelScope.launch {
            locator.marketRepository.getKlinesForChart(symbol, "5min", 100)
                .onSuccess { candles ->
                    _chartState.value = if (candles.isEmpty()) {
                        ChartLoadState.Error("BingX повернув порожній список свічок для $symbol")
                    } else {
                        ChartLoadState.Loaded(candles)
                    }
                }
                .onFailure {
                    AppLogger.w(TAG, "Не вдалось завантажити графік $symbol", it)
                    _chartState.value = ChartLoadState.Error(it.message ?: "Невідома помилка мережі")
                }
            _trades.value = locator.database.tradeDao().getSince(0L).filter { it.symbol == symbol }
        }
    }

    fun retry() {
        val symbol = loadedSymbol ?: return
        loadedSymbol = null
        load(symbol)
    }
}
