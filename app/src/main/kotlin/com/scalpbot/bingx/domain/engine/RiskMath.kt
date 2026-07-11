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

    /** Орієнтовна комісія тейкера BingX Perpetual (%) — використовується лише для нижніх меж SL/TP. */
    const val TAKER_FEE_PERCENT = 0.05

    /**
     * Жорстка нижня межа SL: не менше 0.5% і не менше 4×(спред + 2×комісія тейкера),
     * інакше на низьковолатильній/широкоспредовій парі стоп зʼїдається транзакційними
     * витратами ще до того, як ціна взагалі кудись рухнеться.
     */
    fun minStopLossPercent(spreadPercent: Double, takerFeePercent: Double = TAKER_FEE_PERCENT): Double =
        maxOf(0.5, 4.0 * (spreadPercent + 2.0 * takerFeePercent))

    /** TP має покривати спред+комісії щонайменше втричі, інакше угода не окупає витрати навіть у плюсі. */
    fun minTakeProfitPercent(spreadPercent: Double, takerFeePercent: Double = TAKER_FEE_PERCENT): Double =
        3.0 * (spreadPercent + 2.0 * takerFeePercent)

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
