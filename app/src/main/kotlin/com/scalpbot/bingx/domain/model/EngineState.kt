package com.scalpbot.bingx.domain.model

import com.scalpbot.bingx.data.local.prefs.TradingMode

enum class EngineStatus { STOPPED, RUNNING, PAUSED, KILL_SWITCHED }

data class EngineState(
    val status: EngineStatus = EngineStatus.STOPPED,
    val mode: TradingMode = TradingMode.DEMO,
    val equityUsd: Double = 0.0,
    val pnlTodayUsd: Double = 0.0,
    val pnlTodayPercent: Double = 0.0,
    val openPositionSymbol: String? = null,
    val openPositionDirection: TradeAction? = null,
    val lastError: String? = null,
    val lastUpdatedAtEpochMs: Long = 0L,
)
