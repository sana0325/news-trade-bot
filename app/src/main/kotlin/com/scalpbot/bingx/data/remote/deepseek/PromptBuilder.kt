package com.scalpbot.bingx.data.remote.deepseek

import com.scalpbot.bingx.domain.model.Candle
import com.scalpbot.bingx.domain.model.MarketContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PromptBuilder {

    private val json = Json { encodeDefaults = true }

    fun buildSystemPrompt(): String = """
        Ти — торговий аналітик скальпінг-бота на BingX Perpetual Futures. Твоє єдине
        завдання — за наданими ринковими даними по ОДНІЙ парі вирішити: відкрити лонг,
        відкрити шорт, чи пропустити цю свічку.

        Правила:
        1. Це ЧИСТИЙ СКАЛЬП: горизонт угоди — хвилини. Основа рішення — M5 свічки;
           M15 використовуй ЛИШЕ як контекст загального тренду, не як головний сигнал.
        2. Оцінюй лонг і шорт РІВНОЦІННО. У тебе немає систематичного упередження ні
           в один бік — рішення базується виключно на поточній структурі ціни, обʼємі,
           funding rate і спреді, а не на звичці відкривати частіше в один бік.
        3. Якщо сигналу немає або він слабкий/суперечливий — обери "skip". Skip — це
           нормальний і очікуваний результат більшості опитувань, не намагайся
           знайти угоду там, де її немає.
        4. Якщо в контексті є активні "уроки" з попередніх розборів — врахуй їх,
           це виправлення, отримані з аналізу реальних результатів бота.
        5. Якщо спред задовгий відносно типового TP — це привід для skip.
        6. sl_pct має бути в діапазоні 0.3–0.8 (відсоток від ціни входу),
           tp_pct — у діапазоні 0.5–1.5. Обирай значення в межах цих діапазонів
           виходячи з волатильності поточної пари.

        Відповідай ВИКЛЮЧНО JSON-об'єктом (без markdown, без пояснень поза JSON) точно
        такої форми:
        {"action": "long" | "short" | "skip", "symbol": "<символ або null якщо skip>",
         "sl_pct": <число 0.3-0.8 або null якщо skip>,
         "tp_pct": <число 0.5-1.5 або null якщо skip>,
         "confidence": <число 0-1>, "reason": "<коротке пояснення українською>"}
    """.trimIndent()

    fun buildUserPrompt(context: MarketContext): String {
        val payload = MarketContextPayload.from(context)
        return json.encodeToString(payload)
    }
}

@Serializable
private data class CandlePayload(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
)

@Serializable
private data class OpenPositionPayload(
    val symbol: String,
    val direction: String,
    val entryPrice: Double,
    val currentPrice: Double,
    val pnlPercent: Double,
    val openedMinutesAgo: Long,
)

@Serializable
private data class TradeHistoryPayload(
    val symbol: String,
    val direction: String,
    val pnlPercent: Double,
    val closeReason: String,
)

@Serializable
private data class MarketContextPayload(
    val symbol: String,
    @SerialName("candles_m5") val candlesM5: List<CandlePayload>,
    @SerialName("candles_m15_trend_context") val candlesM15: List<CandlePayload>,
    val spreadPercent: Double,
    val fundingRatePercent: Double,
    val volume24h: Double,
    val openPosition: OpenPositionPayload?,
    val last10Trades: List<TradeHistoryPayload>,
    val activeLessons: List<String>,
) {
    companion object {
        fun from(context: MarketContext): MarketContextPayload = MarketContextPayload(
            symbol = context.symbol,
            candlesM5 = context.candlesM5.map { it.toPayload() },
            candlesM15 = context.candlesM15.map { it.toPayload() },
            spreadPercent = context.spreadPercent,
            fundingRatePercent = context.fundingRatePercent,
            volume24h = context.volume24h,
            openPosition = context.openPosition?.let {
                OpenPositionPayload(it.symbol, it.direction.name.lowercase(), it.entryPrice, it.currentPrice, it.pnlPercent, it.openedMinutesAgo)
            },
            last10Trades = context.recentTrades.map {
                TradeHistoryPayload(it.symbol, it.direction.name.lowercase(), it.pnlPercent, it.closeReason)
            },
            activeLessons = context.activeLessons,
        )

        private fun Candle.toPayload() = CandlePayload(openTimeMs, open, high, low, close, volume)
    }
}
