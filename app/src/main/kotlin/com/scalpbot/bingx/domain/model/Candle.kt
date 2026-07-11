package com.scalpbot.bingx.domain.model

data class Candle(
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)
