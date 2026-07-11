package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.Candle

/**
 * ATR(14) на M5-свічках. Замінює фіксовані SL/TP-відсотки як базу для розрахунку
 * ризику (SlTpCalculator/DecisionValidator) і слугує фільтром волатильного шоку
 * (різкий стрибок true range відносно середнього ATR — типова ознака новинної
 * свічки, яку краще пропустити, а не намагатись торгувати).
 */
object AtrCalculator {

    const val DEFAULT_PERIOD = 14
    const val SHOCK_RATIO_THRESHOLD = 3.0

    /** True range однієї свічки відносно close попередньої. */
    fun trueRange(candle: Candle, previousClose: Double): Double {
        val highLow = candle.high - candle.low
        val highPrevClose = kotlin.math.abs(candle.high - previousClose)
        val lowPrevClose = kotlin.math.abs(candle.low - previousClose)
        return maxOf(highLow, highPrevClose, lowPrevClose)
    }

    /** ATR у абсолютних цінових одиницях за [period] true range з наданих свічок (потрібно period+1 свічок). */
    fun compute(candles: List<Candle>, period: Int = DEFAULT_PERIOD): Double? {
        if (candles.size < period + 1) return null
        val relevant = candles.takeLast(period + 1)
        val trueRanges = relevant.zipWithNext { prev, curr -> trueRange(curr, prev.close) }
        return trueRanges.takeLast(period).average()
    }

    /** ATR як відсоток від останньої ціни закриття — саме це передається в промт DeepSeek. */
    fun computePercent(candles: List<Candle>, period: Int = DEFAULT_PERIOD): Double? {
        val atr = compute(candles, period) ?: return null
        val lastClose = candles.lastOrNull()?.close ?: return null
        if (lastClose <= 0.0) return null
        return atr / lastClose * 100.0
    }

    /**
     * Співвідношення true range найостаннішої (можливо ще не закритої) свічки до
     * ATR(14), порахованого по попередніх свічках. >3 типово означає новинний
     * стрибок волатильності — вхід на такій свічці пропускається (TradingEngine).
     */
    fun shockRatio(candles: List<Candle>, period: Int = DEFAULT_PERIOD): Double? {
        if (candles.size < period + 2) return null
        val history = candles.dropLast(1)
        val atr = compute(history, period) ?: return null
        if (atr <= 0.0) return null
        val last = candles.last()
        val previousClose = history.last().close
        return trueRange(last, previousClose) / atr
    }
}
