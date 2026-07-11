package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import kotlin.math.round

object SlTpCalculator {

    data class Result(val slPrice: Double, val tpPrice: Double)

    fun compute(entryPrice: Double, direction: TradeAction, slPct: Double, tpPct: Double, tickSize: Double): Result {
        require(direction != TradeAction.SKIP) { "SL/TP не рахується для skip" }
        require(entryPrice > 0.0)

        val slFraction = slPct / 100.0
        val tpFraction = tpPct / 100.0

        val (sl, tp) = if (direction == TradeAction.LONG) {
            (entryPrice * (1 - slFraction)) to (entryPrice * (1 + tpFraction))
        } else {
            (entryPrice * (1 + slFraction)) to (entryPrice * (1 - tpFraction))
        }

        return Result(roundToTick(sl, tickSize), roundToTick(tp, tickSize))
    }

    private fun roundToTick(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        return round(price / tick) * tick
    }
}
