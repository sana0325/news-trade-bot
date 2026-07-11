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
 */
object DecisionValidator {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SL_RANGE = 0.3..0.8
    private val TP_RANGE = 0.5..1.5
    private val CONFIDENCE_RANGE = 0.0..1.0

    fun validate(rawResponse: String): DecisionValidationResult {
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

        if (slPct !in SL_RANGE) return DecisionValidationResult.Invalid(rawResponse, "sl_pct поза межами 0.3-0.8: $slPct")
        if (tpPct !in TP_RANGE) return DecisionValidationResult.Invalid(rawResponse, "tp_pct поза межами 0.5-1.5: $tpPct")

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
