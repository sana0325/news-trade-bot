package com.scalpbot.bingx.domain.engine

/**
 * Чиста арифметика ризик-менеджменту, без залежностей від Android/Room —
 * навмисно винесена окремо від RiskManager, щоб kill-switch і денний ліміт
 * можна було юніт-тестити без інстрментальних тестів.
 */
object RiskMath {

    /** Хардкод — з UI не вимикається (RiskManager ігнорує будь-які налаштування тут). */
    const val KILL_SWITCH_DRAWDOWN_PERCENT = 30.0
    const val COOLDOWN_LOSS_STREAK = 3
    const val COOLDOWN_HOURS = 2L

    fun drawdownPercent(baselineEquityUsd: Double, currentEquityUsd: Double): Double {
        if (baselineEquityUsd <= 0.0) return 0.0
        return (baselineEquityUsd - currentEquityUsd) / baselineEquityUsd * 100.0
    }

    fun isKillSwitchTriggered(sessionStartEquityUsd: Double, currentEquityUsd: Double): Boolean =
        drawdownPercent(sessionStartEquityUsd, currentEquityUsd) >= KILL_SWITCH_DRAWDOWN_PERCENT

    fun isDailyLimitExceeded(dayStartEquityUsd: Double, currentEquityUsd: Double, limitPercent: Float): Boolean =
        drawdownPercent(dayStartEquityUsd, currentEquityUsd) >= limitPercent

    /** Спред не повинен зʼїдати надто велику частку цільового TP. */
    fun isSpreadTooWide(spreadPercent: Double, tpPct: Double, maxSpreadFractionOfTp: Double = 0.3): Boolean =
        spreadPercent > tpPct * maxSpreadFractionOfTp
}
