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
        1. Це ЧИСТИЙ СКАЛЬП: горизонт угоди — хвилини/години. Робочий таймфрейм — M5
           свічки; M15 і H1 використовуй ЛИШЕ як контекст загального тренду, не як
           головний сигнал.
        2. Оцінюй лонг і шорт РІВНОЦІННО. У тебе немає систематичного упередження ні
           в один бік — рішення базується виключно на поточній структурі ціни, обʼємі,
           funding rate і спреді, а не на звичці відкривати частіше в один бік.
        3. Якщо сигналу немає або він слабкий/суперечливий — обери "skip". Skip — це
           нормальний і очікуваний результат більшості опитувань, не намагайся
           знайти угоду там, де її немає.
        4. Якщо в контексті є активні "уроки" з попередніх розборів — врахуй їх,
           це виправлення, отримані з аналізу реальних результатів бота.
        5. atrPercent у контексті — це ATR(14) на M5 у % від ціни входу. SL і TP
           рахуй ВІДНОСНО нього, а не як довільний фіксований відсоток:
           sl_pct у діапазоні 1.5–2 × atrPercent, tp_pct — у діапазоні
           2.5–3 × atrPercent. Мінімальне співвідношення ризик/прибуток
           (tp_pct / sl_pct) — 1.5, інакше обирай "skip".
        6. tp_pct обов'язково має покривати спред і комісії щонайменше втричі
           (орієнтовна комісія тейкера ≈0.05% за угоду в один бік): якщо
           tp_pct < 3 × (spreadPercent + 2 × 0.05), угода не окупить витрати
           навіть у разі успіху — обирай "skip".

        Відповідай ВИКЛЮЧНО JSON-об'єктом (без markdown, без пояснень поза JSON) точно
        такої форми:
        {"action": "long" | "short" | "skip", "symbol": "<символ або null якщо skip>",
         "sl_pct": <число ≈1.5-2×atrPercent або null якщо skip>,
         "tp_pct": <число ≈2.5-3×atrPercent або null якщо skip>,
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
    @SerialName("candles_h1_trend_context") val candlesH1: List<CandlePayload>,
    val atrPercent: Double,
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
            candlesH1 = context.candlesH1.map { it.toPayload() },
            atrPercent = context.atrPercent,
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
