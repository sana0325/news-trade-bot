package com.scalpbot.bingx.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskMathTest {

    @Test
    fun `kill switch triggers at exactly 30 percent drawdown`() {
        assertTrue(RiskMath.isKillSwitchTriggered(sessionStartEquityUsd = 1000.0, currentEquityUsd = 700.0))
    }

    @Test
    fun `kill switch triggers beyond 30 percent drawdown`() {
        assertTrue(RiskMath.isKillSwitchTriggered(sessionStartEquityUsd = 1000.0, currentEquityUsd = 500.0))
    }

    @Test
    fun `kill switch does not trigger under 30 percent drawdown`() {
        assertFalse(RiskMath.isKillSwitchTriggered(sessionStartEquityUsd = 1000.0, currentEquityUsd = 750.0))
    }

    @Test
    fun `daily limit exceeded at configured percent`() {
        assertTrue(RiskMath.isDailyLimitExceeded(dayStartEquityUsd = 1000.0, currentEquityUsd = 895.0, limitPercent = 10f))
    }

    @Test
    fun `daily limit not exceeded below configured percent`() {
        assertFalse(RiskMath.isDailyLimitExceeded(dayStartEquityUsd = 1000.0, currentEquityUsd = 950.0, limitPercent = 10f))
    }

    @Test
    fun `spread too wide relative to tp target`() {
        assertTrue(RiskMath.isSpreadTooWide(spreadPercent = 0.5, tpPct = 1.0))
    }

    @Test
    fun `spread acceptable relative to tp target`() {
        assertFalse(RiskMath.isSpreadTooWide(spreadPercent = 0.1, tpPct = 1.0))
    }

    @Test
    fun `equity gain never triggers kill switch`() {
        assertFalse(RiskMath.isKillSwitchTriggered(sessionStartEquityUsd = 1000.0, currentEquityUsd = 1500.0))
    }

    @Test
    fun `min stop loss floor uses the 0point5 percent absolute minimum on a tight spread`() {
        // 4*(0.02+2*0.05)=0.48, менше за 0.5 → перемагає абсолютний мінімум.
        assertEquals(0.5, RiskMath.minStopLossPercent(spreadPercent = 0.02), 1e-9)
    }

    @Test
    fun `min stop loss floor scales with spread once it dominates the absolute minimum`() {
        // 4*(0.3+2*0.05)=1.6 > 0.5 → перемагає формула від спреду.
        assertEquals(1.6, RiskMath.minStopLossPercent(spreadPercent = 0.3), 1e-9)
    }

    @Test
    fun `min take profit floor requires covering spread and fees three times over`() {
        // 3*(0.1+2*0.05)=0.6
        assertEquals(0.6, RiskMath.minTakeProfitPercent(spreadPercent = 0.1), 1e-9)
    }
}
