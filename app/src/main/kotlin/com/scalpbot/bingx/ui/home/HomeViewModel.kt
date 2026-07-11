package com.scalpbot.bingx.ui.home

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.data.local.prefs.TradingMode
import com.scalpbot.bingx.domain.model.EngineState
import com.scalpbot.bingx.domain.model.EngineStatus
import com.scalpbot.bingx.service.TradingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ShiScalpBotApp).serviceLocator

    val engineState: StateFlow<EngineState> = locator.tradingEngine.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onPrimaryActionClick() {
        val mode = locator.secureConfigStore.tradingMode
        when (engineState.value.status) {
            EngineStatus.STOPPED -> {
                if (!locator.secureConfigStore.hasBingxKeysFor(mode)) {
                    _message.value = "Спочатку додайте API-ключі BingX (${mode.name}) у Налаштуваннях"
                    return
                }
                if (!locator.secureConfigStore.hasDeepSeekKey()) {
                    _message.value = "Спочатку додайте ключ DeepSeek у Налаштуваннях"
                    return
                }
                sendAction(TradingForegroundService.ACTION_START)
            }
            EngineStatus.RUNNING -> sendAction(TradingForegroundService.ACTION_PAUSE)
            EngineStatus.PAUSED -> sendAction(TradingForegroundService.ACTION_RESUME)
            EngineStatus.KILL_SWITCHED -> {
                _message.value = "Kill-switch активовано. Скиньте його в Налаштуваннях, щоб продовжити"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun tradingMode(): TradingMode = locator.secureConfigStore.tradingMode

    private fun sendAction(action: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            ContextCompat.startForegroundService(context, TradingForegroundService.intent(context, action))
        }
    }
}
