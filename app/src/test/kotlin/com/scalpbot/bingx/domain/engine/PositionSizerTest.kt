package com.scalpbot.bingx.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionSizerTest {

    @Test
    fun `conservative preset sizes correctly`() {
        // 1000 USDT, 5% маржі, 5x плече -> notional 250 USDT / ціна 100 = 2.5 -> крок 0.001
        val qty = PositionSizer.computeQuantity(
            PositionSizer.Input(
                availableBalanceUsd = 1000.0,
                marginPercent = 5f,
                leverage = 5,
                entryPrice = 100.0,
                stepSize = 0.001,
                minQty = 0.001,
            ),
        )
        assertEquals(2.5, qty, 1e-9)
    }

    @Test
    fun `aggressive preset sizes correctly`() {
        // 1000 USDT, 50% маржі, 20x плече -> notional 10000 / ціна 100 = 100
        val qty = PositionSizer.computeQuantity(
            PositionSizer.Input(
                availableBalanceUsd = 1000.0,
                marginPercent = 50f,
                leverage = 20,
                entryPrice = 100.0,
                stepSize = 1.0,
                minQty = 1.0,
            ),
        )
        assertEquals(100.0, qty, 1e-9)
    }

    @Test
    fun `rounds down to step size`() {
        val qty = PositionSizer.computeQuantity(
            PositionSizer.Input(
                availableBalanceUsd = 100.0,
                marginPercent = 5f,
                leverage = 5,
                entryPrice = 3.0,
                stepSize = 0.1,
                minQty = 0.1,
            ),
        )
        // notional = 25, qty = 8.333... -> округлено вниз до кроку 0.1 = 8.3
        assertEquals(8.3, qty, 1e-9)
    }

    @Test
    fun `returns zero when below minQty`() {
        val qty = PositionSizer.computeQuantity(
            PositionSizer.Input(
                availableBalanceUsd = 10.0,
                marginPercent = 5f,
                leverage = 5,
                entryPrice = 50000.0,
                stepSize = 0.001,
                minQty = 0.001,
            ),
        )
        assertEquals(0.0, qty, 1e-9)
    }
}
