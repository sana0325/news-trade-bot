package com.scalpbot.bingx.domain.engine

import kotlin.math.floor

/**
 * Розмір позиції з маржі% депозиту і плеча, округлений вниз до stepSize біржі.
 * Повертає 0.0, якщо результат менший за minQty — двигун трактує це як skip
 * (депозит замалий для поточних налаштувань ризику на цій парі).
 */
object PositionSizer {

    data class Input(
        val availableBalanceUsd: Double,
        val marginPercent: Float,
        val leverage: Int,
        val entryPrice: Double,
        val stepSize: Double,
        val minQty: Double,
    )

    fun computeQuantity(input: Input): Double {
        require(input.entryPrice > 0.0) { "entryPrice має бути додатним" }
        val marginUsd = input.availableBalanceUsd * (input.marginPercent / 100.0)
        val notionalUsd = marginUsd * input.leverage
        val rawQuantity = notionalUsd / input.entryPrice
        val steppedQuantity = floorToStep(rawQuantity, input.stepSize)
        return if (steppedQuantity < input.minQty) 0.0 else steppedQuantity
    }

    fun marginUsdFor(availableBalanceUsd: Double, marginPercent: Float): Double =
        availableBalanceUsd * (marginPercent / 100.0)

    private fun floorToStep(quantity: Double, step: Double): Double {
        if (step <= 0.0) return quantity
        return floor(quantity / step) * step
    }
}
