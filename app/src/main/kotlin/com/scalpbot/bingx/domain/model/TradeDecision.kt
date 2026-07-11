package com.scalpbot.bingx.domain.model

enum class TradeAction { LONG, SHORT, SKIP }

data class TradeDecision(
    val action: TradeAction,
    val symbol: String?,
    val slPct: Double?,
    val tpPct: Double?,
    val confidence: Double,
    val reason: String,
)
