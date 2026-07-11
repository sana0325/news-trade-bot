package com.scalpbot.bingx.domain.model

data class OpenPositionSummary(
    val symbol: String,
    val direction: TradeAction,
    val entryPrice: Double,
    val currentPrice: Double,
    val pnlPercent: Double,
    val openedMinutesAgo: Long,
)

data class TradeHistorySummary(
    val symbol: String,
    val direction: TradeAction,
    val pnlPercent: Double,
    val closeReason: String,
)

/** Усе, що двигун збирає для одного опитування DeepSeek по одній парі. */
data class MarketContext(
    val symbol: String,
    val candlesM1: List<Candle>,
    val candlesM5: List<Candle>,
    val candlesM15: List<Candle>,
    val spreadPercent: Double,
    val fundingRatePercent: Double,
    val volume24h: Double,
    val openPosition: OpenPositionSummary?,
    val recentTrades: List<TradeHistorySummary>,
    val activeLessons: List<String>,
)
