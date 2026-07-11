package com.scalpbot.bingx.data.remote.bingx

import com.scalpbot.bingx.data.local.prefs.TradingMode

/**
 * DEMO (BingX VST — Virtual/Simulated Trading) і LIVE торгують через РІЗНІ
 * хости REST API; ключ демо-акаунта прив'язаний саме до vst-хоста.
 * Публічний ринковий WebSocket спільний для обох режимів (лише публічні дані).
 */
object BingXEndpoints {

    fun restBaseUrl(mode: TradingMode): String = when (mode) {
        TradingMode.DEMO -> "https://open-api-vst.bingx.com"
        TradingMode.LIVE -> "https://open-api.bingx.com"
    }

    const val MARKET_WS_URL = "wss://open-api-swap.bingx.com/swap-market"

    fun userDataWsUrl(listenKey: String): String = "$MARKET_WS_URL?listenKey=$listenKey"
}
