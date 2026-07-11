package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.domain.model.TradeAction
import com.scalpbot.bingx.domain.model.TradeDecision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface DecisionValidationResult {
    data class Valid(val decision: TradeDecision) : DecisionValidationResult
    data class Invalid(val rawResponse: String, val error: String) : DecisionValidationResult
}

/**
 * Валідує сиру відповідь DeepSeek проти строгого контракту {action, symbol, sl_pct,
 * tp_pct, confidence, reason}. Будь-яка невідповідність (невалідний JSON, пропущене
 * поле, значення поза діапазоном) → Invalid, і TradingEngine трактує це як skip.
 *
 * sl_pct/tp_pct більше не звіряються з фіксованими діапазонами — вони залежать від
 * поточної волатильності (ATR) і транзакційних витрат (спред+комісії) конкретної
 * пари/моменту, тому діапазони рахуються тут же з [atrPercent]/[spreadPercent].
 */
object DecisionValidator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val CONFIDENCE_RANGE = 0.0..1.0

    // Валідні межі тут навмисно ширші за рекомендовані DeepSeek у system-промті
    // (SL 1.5-2x, TP 2.5-3x ATR) — невеликий запас, а не жорсткий збіг один-в-один.
    private const val SL_ATR_MULTIPLIER_MIN = 1.0
    private const val SL_ATR_MULTIPLIER_MAX = 2.5
    private const val TP_ATR_MULTIPLIER_MIN = 2.0
    private const val TP_ATR_MULTIPLIER_MAX = 4.0

    /** Мінімальне співвідношення ризик/прибуток tp_pct/sl_pct. */
    private const val MIN_RISK_REWARD = 1.5

    fun validate(rawResponse: String, atrPercent: Double, spreadPercent: Double): DecisionValidationResult {
        val dto = try {
            json.decodeFromString(RawDecisionDto.serializer(), extractJsonObject(rawResponse))
        } catch (e: Exception) {
            return DecisionValidationResult.Invalid(rawResponse, "Помилка парсингу JSON: ${e.message}")
        }

        val action = when (dto.action?.trim()?.lowercase()) {
            "long" -> TradeAction.LONG
            "short" -> TradeAction.SHORT
            "skip" -> TradeAction.SKIP
            else -> return DecisionValidationResult.Invalid(rawResponse, "Невідома дія: ${dto.action}")
        }

        val confidence = dto.confidence ?: return DecisionValidationResult.Invalid(rawResponse, "Відсутній confidence")
        if (confidence !in CONFIDENCE_RANGE) {
            return DecisionValidationResult.Invalid(rawResponse, "confidence поза межами 0-1: $confidence")
        }

        if (action == TradeAction.SKIP) {
            return DecisionValidationResult.Valid(
                TradeDecision(TradeAction.SKIP, null, null, null, confidence, dto.reason ?: ""),
            )
        }

        val symbol = dto.symbol?.trim()?.takeIf { it.isNotBlank() }
            ?: return DecisionValidationResult.Invalid(rawResponse, "Відсутній symbol для дії ${dto.action}")
        val slPct = dto.slPct ?: return DecisionValidationResult.Invalid(rawResponse, "Відсутній sl_pct")
        val tpPct = dto.tpPct ?: return DecisionValidationResult.Invalid(rawResponse, "Відсутній tp_pct")

        // ATR-відносні межі: якщо ATR аномально малий, нижня межа підіймається до
        // жорсткого мінімуму (spreadPercent+комісії) — інакше SL/TP зʼїдались би
        // транзакційними витратами ще до того, як спрацював би сигнал.
        val slLower = maxOf(atrPercent * SL_ATR_MULTIPLIER_MIN, RiskMath.minStopLossPercent(spreadPercent))
        val slUpper = atrPercent * SL_ATR_MULTIPLIER_MAX
        if (slPct < slLower || slPct > slUpper) {
            return DecisionValidationResult.Invalid(
                rawResponse,
                "sl_pct $slPct поза межами $slLower-$slUpper (ATR=$atrPercent%, спред=$spreadPercent%)",
            )
        }

        val tpLower = maxOf(atrPercent * TP_ATR_MULTIPLIER_MIN, RiskMath.minTakeProfitPercent(spreadPercent))
        val tpUpper = atrPercent * TP_ATR_MULTIPLIER_MAX
        if (tpPct < tpLower || tpPct > tpUpper) {
            return DecisionValidationResult.Invalid(
                rawResponse,
                "tp_pct $tpPct поза межами $tpLower-$tpUpper (ATR=$atrPercent%, спред=$spreadPercent%)",
            )
        }

        if (tpPct < slPct * MIN_RISK_REWARD) {
            return DecisionValidationResult.Invalid(rawResponse, "RR ${tpPct / slPct} менше мінімального $MIN_RISK_REWARD")
        }

        return DecisionValidationResult.Valid(
            TradeDecision(action, symbol, slPct, tpPct, confidence, dto.reason ?: ""),
        )
    }

    /** DeepSeek іноді обгортає JSON у markdown code fence попри response_format=json_object. */
    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }
}

@Serializable
private data class RawDecisionDto(
    val action: String? = null,
    val symbol: String? = null,
    @SerialName("sl_pct") val slPct: Double? = null,
    @SerialName("tp_pct") val tpPct: Double? = null,
    val confidence: Double? = null,
    val reason: String? = null,
)
