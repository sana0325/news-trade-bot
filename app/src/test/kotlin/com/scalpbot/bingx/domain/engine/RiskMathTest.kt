package com.scalpbot.bingx.domain.engine

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
}
