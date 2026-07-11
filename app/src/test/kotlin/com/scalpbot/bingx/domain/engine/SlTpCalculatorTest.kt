package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlTpCalculatorTest {

    @Test
    fun `long sl below entry and tp above entry`() {
        val result = SlTpCalculator.compute(
            entryPrice = 100.0,
            direction = TradeAction.LONG,
            slPct = 0.5,
            tpPct = 1.0,
            tickSize = 0.01,
        )
        assertEquals(99.5, result.slPrice, 1e-9)
        assertEquals(101.0, result.tpPrice, 1e-9)
    }

    @Test
    fun `short sl above entry and tp below entry`() {
        val result = SlTpCalculator.compute(
            entryPrice = 100.0,
            direction = TradeAction.SHORT,
            slPct = 0.5,
            tpPct = 1.0,
            tickSize = 0.01,
        )
        assertEquals(100.5, result.slPrice, 1e-9)
        assertEquals(99.0, result.tpPrice, 1e-9)
    }

    @Test
    fun `rounds prices to tick size`() {
        val result = SlTpCalculator.compute(
            entryPrice = 100.037,
            direction = TradeAction.LONG,
            slPct = 0.5,
            tpPct = 1.0,
            tickSize = 0.1,
        )
        assertEquals(99.5, result.slPrice, 1e-9)
        assertEquals(101.0, result.tpPrice, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws for skip direction`() {
        SlTpCalculator.compute(100.0, TradeAction.SKIP, 0.5, 1.0, 0.01)
    }

    @Test
    fun `sl-tp from ATR percent lands within recommended multiplier band`() {
        // entry 100000, ATR 0.6% — множники в межах рекомендованого діапазону
        // system-промту DeepSeek (SL 1-1.5x, TP 2.5-3x ATR), а не довільний фікс.
        val result = SlTpCalculator.fromAtrPercent(
            entryPrice = 100000.0,
            direction = TradeAction.LONG,
            atrPercent = 0.6,
            slMultiplier = 1.25,
            tpMultiplier = 2.75,
            tickSize = 0.1,
        )
        assertTrue("SL ${result.slPrice} not in [99100, 99400]", result.slPrice in 99100.0..99400.0)
        assertTrue("TP ${result.tpPrice} not in [101500, 101800]", result.tpPrice in 101500.0..101800.0)
    }

    @Test
    fun `sl-tp from ATR percent for short mirrors long around entry`() {
        val result = SlTpCalculator.fromAtrPercent(
            entryPrice = 100000.0,
            direction = TradeAction.SHORT,
            atrPercent = 0.6,
            slMultiplier = 1.25,
            tpMultiplier = 2.75,
            tickSize = 0.1,
        )
        assertTrue(result.slPrice in 100600.0..100900.0)
        assertTrue(result.tpPrice in 98200.0..98500.0)
    }
}
