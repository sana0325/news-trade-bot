package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtrCalculatorTest {

    /** 15 стабільних свічок: high-low=2.0, close крокує +1 щоразу — true range стабільно 2.0. */
    private fun steadyCandles(count: Int = 15, rangeWidth: Double = 2.0, startClose: Double = 100000.0): List<Candle> =
        (0 until count).map { i ->
            val close = startClose + i
            Candle(
                openTimeMs = i * 300_000L,
                open = close - rangeWidth / 2,
                high = close + rangeWidth / 2,
                low = close - rangeWidth / 2,
                close = close,
                volume = 10.0,
            )
        }

    @Test
    fun `compute returns null with insufficient candles`() {
        assertNull(AtrCalculator.compute(steadyCandles(count = 10)))
    }

    @Test
    fun `compute averages true range over the period`() {
        val atr = AtrCalculator.compute(steadyCandles())
        assertEquals(2.0, atr!!, 1e-6)
    }

    @Test
    fun `computePercent expresses ATR relative to last close`() {
        val candles = steadyCandles(rangeWidth = 600.0, startClose = 100000.0)
        val atrPercent = AtrCalculator.computePercent(candles)
        // ATR=600, останній close=100014 → ~0.5997%
        assertTrue(atrPercent!! in 0.55..0.65)
    }

    @Test
    fun `shockRatio is near 1 for steady volatility`() {
        val candles = steadyCandles(count = 16)
        val ratio = AtrCalculator.shockRatio(candles)
        assertTrue(ratio!! in 0.9..1.1)
    }

    @Test
    fun `shockRatio flags a news-spike candle above threshold`() {
        val steady = steadyCandles(count = 16)
        val last = steady.last()
        val spike = last.copy(high = last.close + 20.0, low = last.close - 20.0)
        val candles = steady.dropLast(1) + spike

        val ratio = AtrCalculator.shockRatio(candles)
        assertTrue("expected shock ratio > ${AtrCalculator.SHOCK_RATIO_THRESHOLD}, was $ratio", ratio!! > AtrCalculator.SHOCK_RATIO_THRESHOLD)
    }
}
