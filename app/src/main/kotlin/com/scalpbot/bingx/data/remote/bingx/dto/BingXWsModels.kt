package com.scalpbot.bingx.data.remote.bingx.dto

sealed interface MarketWsEvent {
    data class KlineUpdate(
        val symbol: String,
        val interval: String,
        val kline: KlineDto,
    ) : MarketWsEvent

    data class TickerUpdate(
        val symbol: String,
        val lastPrice: Double,
        val priceChangePercent: Double,
    ) : MarketWsEvent

    data object Connected : MarketWsEvent
    data class Disconnected(val cause: Throwable?) : MarketWsEvent
}
