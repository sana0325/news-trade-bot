package com.scalpbot.bingx.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.local.db.entity.TradeStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

data class StatsSummary(
    val closedTradeCount: Int = 0,
    val winratePercent: Double = 0.0,
    val profitFactor: Double = 0.0,
    val avgPnlUsd: Double = 0.0,
    val totalPnlUsd: Double = 0.0,
    val equityCurve: List<Double> = emptyList(),
    val pnlByPair: List<Pair<String, Double>> = emptyList(),
    val longPnlUsd: Double = 0.0,
    val shortPnlUsd: Double = 0.0,
    val longCount: Int = 0,
    val shortCount: Int = 0,
)

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ScalpBotApp).serviceLocator
    private val tradeDao = locator.database.tradeDao()

    val summary: StateFlow<StatsSummary> = tradeDao.observeAll()
        .map(::computeSummary)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())

    private fun computeSummary(trades: List<TradeEntity>): StatsSummary {
        val closed = trades.filter { it.status == TradeStatus.CLOSED }.sortedBy { it.closedAtEpochMs }
        if (closed.isEmpty()) return StatsSummary()

        val wins = closed.filter { (it.pnlUsd ?: 0.0) >= 0 }
        val losses = closed.filter { (it.pnlUsd ?: 0.0) < 0 }
        val grossProfit = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = losses.sumOf { it.pnlUsd ?: 0.0 }
        val profitFactor = when {
            grossLoss == 0.0 && grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            grossLoss == 0.0 -> 0.0
            else -> grossProfit / abs(grossLoss)
        }
        val totalPnl = closed.sumOf { it.pnlUsd ?: 0.0 }

        var cumulative = 0.0
        val equityCurve = closed.map { trade ->
            cumulative += trade.pnlUsd ?: 0.0
            cumulative
        }

        val pnlByPair = closed.groupBy { it.symbol }
            .mapValues { (_, list) -> list.sumOf { it.pnlUsd ?: 0.0 } }
            .toList()
            .sortedByDescending { it.second }

        val longTrades = closed.filter { it.direction == TradeDirection.LONG }
        val shortTrades = closed.filter { it.direction == TradeDirection.SHORT }

        return StatsSummary(
            closedTradeCount = closed.size,
            winratePercent = wins.size.toDouble() / closed.size * 100.0,
            profitFactor = profitFactor,
            avgPnlUsd = totalPnl / closed.size,
            totalPnlUsd = totalPnl,
            equityCurve = equityCurve,
            pnlByPair = pnlByPair,
            longPnlUsd = longTrades.sumOf { it.pnlUsd ?: 0.0 },
            shortPnlUsd = shortTrades.sumOf { it.pnlUsd ?: 0.0 },
            longCount = longTrades.size,
            shortCount = shortTrades.size,
        )
    }
}
