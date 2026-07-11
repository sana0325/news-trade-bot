package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import org.junit.Assert.assertEquals
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
}
