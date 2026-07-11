package com.scalpbot.bingx.ui.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.domain.model.PairTicker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MarketViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ShiScalpBotApp).serviceLocator
    val tickers: StateFlow<List<PairTicker>> = locator.marketRepository.pairTickers

    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        locator.marketRepository.start(viewModelScope)
    }

    fun setPairEnabled(symbol: String, enabled: Boolean) {
        viewModelScope.launch { locator.marketRepository.setPairEnabled(symbol, enabled) }
    }
}
