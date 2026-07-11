package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import kotlin.math.round

object SlTpCalculator {

    data class Result(val slPrice: Double, val tpPrice: Double)

    /**
     * [slPct]/[tpPct] — відсотки від ціни входу як звичайні числа (0.5 означає 0.5%,
     * НЕ 0.005) — ділимо на 100.0 рівно один раз тут. Значення приходять уже готовими
     * відсотками від DeepSeek (через DecisionValidator) або з [fromAtrPercent] нижче,
     * так що подвійного ділення на 100 в конвеєрі бути не повинно.
     */
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

    /**
     * Розраховує sl_pct/tp_pct як [atrPercent] × множник (рекомендовані діапазони —
     * SL 1.5-2×ATR, TP 2.5-3×ATR, див. system-промт DeepSeek) і одразу переводить у
     * ціни. [atrPercent] — вже відсоток (0.6 означає 0.6%), тому множення тут не ділить
     * нічого на 100 повторно — це робить лише [compute] всередині.
     */
    fun fromAtrPercent(
        entryPrice: Double,
        direction: TradeAction,
        atrPercent: Double,
        slMultiplier: Double,
        tpMultiplier: Double,
        tickSize: Double,
    ): Result = compute(entryPrice, direction, atrPercent * slMultiplier, atrPercent * tpMultiplier, tickSize)

    private fun roundToTick(price: Double, tick: Double): Double {
        if (tick <= 0.0) return price
        return round(price / tick) * tick
    }
}
