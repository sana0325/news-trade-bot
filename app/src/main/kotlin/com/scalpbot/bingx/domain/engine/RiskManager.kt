package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.data.local.db.dao.TradeDao
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import java.time.LocalDate
import java.time.ZoneOffset

sealed interface RiskCheckResult {
    data object Allowed : RiskCheckResult
    data class Blocked(val reason: String) : RiskCheckResult
}

/**
 * Стан (kill-switch, денний ліміт, cooldown, ліміт угод/день) зберігається в
 * SecureConfigStore/Room; сама арифметика — у RiskMath (юніт-тестована окремо).
 */
class RiskManager(
    private val secureConfigStore: SecureConfigStore,
    private val tradeDao: TradeDao,
) {

    /** Викликати одразу при першому запуску двигуна (і лише тоді) — фіксує baseline для kill-switch. */
    fun ensureSessionBaseline(currentEquityUsd: Double) {
        if (secureConfigStore.sessionStartEquityUsd == null) {
            secureConfigStore.sessionStartEquityUsd = currentEquityUsd
        }
    }

    fun resetSession(currentEquityUsd: Double) {
        secureConfigStore.sessionStartEquityUsd = currentEquityUsd
        secureConfigStore.killSwitchTriggered = false
    }

    /** true, якщо kill-switch щойно спрацював або вже був активний. Хардкод — з UI не вимикається. */
    fun checkKillSwitch(currentEquityUsd: Double): Boolean {
        if (secureConfigStore.killSwitchTriggered) return true
        val start = secureConfigStore.sessionStartEquityUsd ?: return false
        if (RiskMath.isKillSwitchTriggered(start, currentEquityUsd)) {
            secureConfigStore.killSwitchTriggered = true
            return true
        }
        return false
    }

    suspend fun canOpenNewTrade(currentEquityUsd: Double): RiskCheckResult {
        if (secureConfigStore.killSwitchTriggered) return RiskCheckResult.Blocked("Kill-switch активовано — торгівля зупинена")

        ensureDayBoundary(currentEquityUsd)
        val dayStart = secureConfigStore.dayStartEquityUsd
        if (dayStart != null && RiskMath.isDailyLimitExceeded(dayStart, currentEquityUsd, secureConfigStore.dailyLossLimitPercent)) {
            return RiskCheckResult.Blocked("Досягнуто денний ліміт збитку (-${secureConfigStore.dailyLossLimitPercent}%), пауза до наступної доби")
        }

        val now = System.currentTimeMillis()
        if (secureConfigStore.cooldownUntilEpochMs > now) {
            return RiskCheckResult.Blocked("Cooldown після серії стопів активний")
        }

        val tradesToday = tradeDao.countSince(startOfTodayEpochMs())
        if (tradesToday >= secureConfigStore.maxTradesPerDay) {
            return RiskCheckResult.Blocked("Досягнуто ліміт угод на день (${secureConfigStore.maxTradesPerDay})")
        }

        return RiskCheckResult.Allowed
    }

    /** Викликати після кожного закриття угоди — оновлює cooldown-стан. */
    suspend fun onTradeClosed() {
        val lastLosses = tradeDao.countLastLosses(RiskMath.COOLDOWN_LOSS_STREAK)
        if (lastLosses == RiskMath.COOLDOWN_LOSS_STREAK) {
            secureConfigStore.cooldownUntilEpochMs = System.currentTimeMillis() + RiskMath.COOLDOWN_HOURS * 60 * 60_000L
        }
    }

    private fun ensureDayBoundary(currentEquityUsd: Double) {
        val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
        if (secureConfigStore.dayStartEpochDay != today) {
            secureConfigStore.dayStartEpochDay = today
            secureConfigStore.dayStartEquityUsd = currentEquityUsd
        }
    }

    private fun startOfTodayEpochMs(): Long =
        LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
