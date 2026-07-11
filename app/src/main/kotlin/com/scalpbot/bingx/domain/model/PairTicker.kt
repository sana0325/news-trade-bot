package com.scalpbot.bingx.domain.model

data class PairTicker(
    val symbol: String,
    val baseAsset: String,
    val lastPrice: Double,
    val priceChangePercent24h: Double,
    val volume24h: Double,
    val sparkline: List<Double>,
    val enabled: Boolean,
)
