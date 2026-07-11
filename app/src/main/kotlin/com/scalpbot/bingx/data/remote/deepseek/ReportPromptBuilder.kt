package com.scalpbot.bingx.data.remote.deepseek

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ReportPromptBuilder {

    private val json = Json { encodeDefaults = true }

    fun buildSystemPrompt(): String = """
        Ти — аналітик результатів скальпінг-бота. Тобі дають статистику за останні
        ~2 доби торгівлі (winrate, profit factor, середній PnL, найкращі/найгірші
        пари, лонг проти шорта, серії збитків). Твоє завдання:
        1. Дати короткий текстовий вердикт УКРАЇНСЬКОЮ — що працює, що ні, чому.
        2. Сформулювати 2-5 конкретних, застосовних корективів ("уроків") до
           торгового промта скальп-бота — коротких, конкретних інструкцій
           українською, які можна додати в наступні опитування ШІ перед відкриттям
           угод (напр. "Уникай входів у BTC-USDT на M1 проти тренду M15",
           "Знижуй впевненість при funding rate > 0.05%").
        Не пропонуй уроків, якщо даних замало для висновку — краще порожній список,
        ніж вигаданий патерн.

        Відповідай ВИКЛЮЧНО JSON-об'єктом такої форми:
        {"verdict": "<текст вердикту українською>", "lessons": ["<урок 1>", "<урок 2>", ...]}
    """.trimIndent()

    fun buildUserPrompt(stats: ReportStatsPayload): String = json.encodeToString(stats)
}

@Serializable
data class ReportStatsPayload(
    val periodStart: String,
    val periodEnd: String,
    val tradeCount: Int,
    val winratePercent: Double,
    val profitFactor: Double,
    val avgPnlUsd: Double,
    val totalPnlUsd: Double,
    val bestPair: String?,
    val worstPair: String?,
    val longPnlUsd: Double,
    val shortPnlUsd: Double,
    val longCount: Int,
    val shortCount: Int,
    val maxLossStreak: Int,
    val previousLessons: List<String>,
)
