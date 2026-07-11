package com.scalpbot.bingx.ui.settings

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import com.scalpbot.bingx.data.local.prefs.RiskPreset
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import com.scalpbot.bingx.data.local.prefs.TradingMode
import com.scalpbot.bingx.service.TradingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val tradingMode: TradingMode = TradingMode.DEMO,
    val riskPreset: RiskPreset = RiskPreset.CONSERVATIVE,
    val marginPercent: Float = RiskPreset.CONSERVATIVE.marginPercent,
    val leverage: Int = RiskPreset.CONSERVATIVE.leverage,
    val maxHoldMinutes: Int = 90,
    val dailyLossLimitPercent: Float = 10f,
    val maxTradesPerDay: Int = 15,
    val killSwitchTriggered: Boolean = false,
    val bingxDemoApiKey: String = "",
    val bingxDemoApiSecret: String = "",
    val bingxLiveApiKey: String = "",
    val bingxLiveApiSecret: String = "",
    val deepseekApiKey: String = "",
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ScalpBotApp).serviceLocator
    private val config: SecureConfigStore = locator.secureConfigStore

    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    val pairs: StateFlow<List<PairCacheEntity>> = locator.database.pairDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun readState() = SettingsUiState(
        tradingMode = config.tradingMode,
        riskPreset = config.riskPreset,
        marginPercent = config.marginPercent,
        leverage = config.leverage,
        maxHoldMinutes = config.maxHoldMinutes,
        dailyLossLimitPercent = config.dailyLossLimitPercent,
        maxTradesPerDay = config.maxTradesPerDay,
        killSwitchTriggered = config.killSwitchTriggered,
        bingxDemoApiKey = config.bingxDemoApiKey,
        bingxDemoApiSecret = config.bingxDemoApiSecret,
        bingxLiveApiKey = config.bingxLiveApiKey,
        bingxLiveApiSecret = config.bingxLiveApiSecret,
        deepseekApiKey = config.deepseekApiKey,
    )

    private fun refresh() {
        _uiState.value = readState()
    }

    fun setTradingMode(mode: TradingMode) {
        config.tradingMode = mode
        refresh()
    }

    fun setRiskPreset(preset: RiskPreset) {
        config.riskPreset = preset
        refresh()
    }

    fun setCustomRisk(marginPercent: Float, leverage: Int) {
        config.riskPreset = RiskPreset.CUSTOM
        config.marginPercent = marginPercent
        config.leverage = leverage
        refresh()
    }

    fun setMaxHoldMinutes(value: Int) {
        config.maxHoldMinutes = value
        refresh()
    }

    fun setDailyLossLimitPercent(value: Float) {
        config.dailyLossLimitPercent = value
        refresh()
    }

    fun setMaxTradesPerDay(value: Int) {
        config.maxTradesPerDay = value
        refresh()
    }

    fun setBingxDemoKeys(key: String, secret: String) {
        config.bingxDemoApiKey = key
        config.bingxDemoApiSecret = secret
        refresh()
    }

    fun setBingxLiveKeys(key: String, secret: String) {
        config.bingxLiveApiKey = key
        config.bingxLiveApiSecret = secret
        refresh()
    }

    fun setDeepSeekKey(key: String) {
        config.deepseekApiKey = key
        refresh()
    }

    fun resetKillSwitch() {
        config.killSwitchTriggered = false
        config.sessionStartEquityUsd = null
        refresh()
    }

    fun setPairEnabled(symbol: String, enabled: Boolean) {
        viewModelScope.launch { locator.database.pairDao().setEnabled(symbol, enabled) }
    }

    fun pause() = sendAction(TradingForegroundService.ACTION_PAUSE)
    fun resume() = sendAction(TradingForegroundService.ACTION_RESUME)
    fun stop() = sendAction(TradingForegroundService.ACTION_STOP)
    fun closeAll() = sendAction(TradingForegroundService.ACTION_CLOSE_ALL)

    private fun sendAction(action: String) {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, TradingForegroundService.intent(context, action))
    }
}
