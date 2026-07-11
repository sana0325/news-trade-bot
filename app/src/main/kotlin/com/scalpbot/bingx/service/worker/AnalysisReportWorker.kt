package com.scalpbot.bingx.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.LessonEntity
import com.scalpbot.bingx.data.local.db.entity.ReportEntity
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.remote.deepseek.ReportPromptBuilder
import com.scalpbot.bingx.data.remote.deepseek.ReportStatsPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AnalysisReportWorker"
private const val PERIODIC_WORK_NAME = "scalpbot_analysis_report"
private const val PERIOD_HOURS = 48L

/**
 * Раз на 2 дні: формує статзвіт по неаналізованих закритих угодах, шле в
 * DeepSeek за вердиктом і корективами, зберігає звіт + нову версію "уроків"
 * (стару версію завжди можна активувати назад у Журналі).
 */
class AnalysisReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun doWork(): Result {
        val locator = (applicationContext as ShiScalpBotApp).serviceLocator
        val tradeDao = locator.database.tradeDao()
        val reportDao = locator.database.reportDao()
        val lessonDao = locator.database.lessonDao()

        val now = System.currentTimeMillis()
        val trades = tradeDao.getUnanalyzedClosedUntil(now)
        if (trades.isEmpty()) {
            AppLogger.i(TAG, "Немає нових закритих угод для розбору")
            return Result.success()
        }

        val periodStart = trades.minOf { it.openedAtEpochMs }
        val activeLessons = lessonDao.getActive()?.let {
            runCatching { json.decodeFromString<List<String>>(it.contentJson) }.getOrDefault(emptyList())
        } ?: emptyList()

        val stats = computeStats(trades, periodStart, now, activeLessons)
        val statsJson = json.encodeToString(ReportStatsPayload.serializer(), stats)

        val raw = locator.deepSeekClient.chat(
            ReportPromptBuilder.buildSystemPrompt(),
            ReportPromptBuilder.buildUserPrompt(stats),
        ).getOrElse {
            AppLogger.w(TAG, "DeepSeek розбір не вдався", it)
            return Result.retry()
        }

        val verdict = parseVerdict(raw)

        val reportId = reportDao.insert(
            ReportEntity(
                periodStartEpochMs = periodStart,
                periodEndEpochMs = now,
                createdAtEpochMs = now,
                statsJson = statsJson,
                verdictText = verdict.verdict.ifBlank { "DeepSeek не надав текстового вердикту." },
                producedLessonsVersion = null,
                tradeCount = trades.size,
            ),
        )

        if (verdict.lessons.isNotEmpty()) {
            val nextVersion = (lessonDao.getMaxVersion() ?: 0) + 1
            lessonDao.insertAsActive(
                LessonEntity(
                    version = nextVersion,
                    createdAtEpochMs = now,
                    contentJson = json.encodeToString(verdict.lessons),
                    sourceReportId = reportId,
                    isActive = true,
                ),
            )
        }

        tradeDao.markAnalyzed(trades.map { it.id }, reportId)
        locator.tradeNotifier.onReportReady()
        AppLogger.i(TAG, "Звіт #$reportId готовий, угод у періоді: ${trades.size}")
        return Result.success()
    }

    private fun computeStats(
        trades: List<TradeEntity>,
        periodStart: Long,
        periodEnd: Long,
        previousLessons: List<String>,
    ): ReportStatsPayload {
        val closed = trades.sortedBy { it.closedAtEpochMs ?: 0L }
        val wins = closed.filter { (it.pnlUsd ?: 0.0) >= 0 }
        val losses = closed.filter { (it.pnlUsd ?: 0.0) < 0 }
        val grossProfit = wins.sumOf { it.pnlUsd ?: 0.0 }
        val grossLoss = losses.sumOf { it.pnlUsd ?: 0.0 }
        val profitFactor = when {
            grossLoss == 0.0 && grossProfit > 0.0 -> 0.0 // немає на що ділити — трактуємо як "не визначено" (0 в JSON, не Infinity)
            grossLoss == 0.0 -> 0.0
            else -> grossProfit / abs(grossLoss)
        }
        val totalPnl = closed.sumOf { it.pnlUsd ?: 0.0 }
        val byPair = closed.groupBy { it.symbol }.mapValues { (_, v) -> v.sumOf { it.pnlUsd ?: 0.0 } }
        val bestPair = byPair.maxByOrNull { it.value }?.key
        val worstPair = byPair.minByOrNull { it.value }?.key
        val longTrades = closed.filter { it.direction == TradeDirection.LONG }
        val shortTrades = closed.filter { it.direction == TradeDirection.SHORT }

        var maxStreak = 0
        var currentStreak = 0
        closed.forEach { trade ->
            if ((trade.pnlUsd ?: 0.0) < 0) {
                currentStreak++
                maxStreak = maxOf(maxStreak, currentStreak)
            } else {
                currentStreak = 0
            }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return ReportStatsPayload(
            periodStart = dateFormat.format(Date(periodStart)),
            periodEnd = dateFormat.format(Date(periodEnd)),
            tradeCount = closed.size,
            winratePercent = if (closed.isEmpty()) 0.0 else wins.size.toDouble() / closed.size * 100.0,
            profitFactor = profitFactor,
            avgPnlUsd = if (closed.isEmpty()) 0.0 else totalPnl / closed.size,
            totalPnlUsd = totalPnl,
            bestPair = bestPair,
            worstPair = worstPair,
            longPnlUsd = longTrades.sumOf { it.pnlUsd ?: 0.0 },
            shortPnlUsd = shortTrades.sumOf { it.pnlUsd ?: 0.0 },
            longCount = longTrades.size,
            shortCount = shortTrades.size,
            maxLossStreak = maxStreak,
            previousLessons = previousLessons,
        )
    }

    private fun parseVerdict(raw: String): ReportVerdictDto = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val jsonText = if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
        json.decodeFromString(ReportVerdictDto.serializer(), jsonText)
    } catch (e: Exception) {
        AppLogger.w(TAG, "Не вдалось розпарсити вердикт DeepSeek", e)
        ReportVerdictDto(verdict = raw.take(500), lessons = emptyList())
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnalysisReportWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

@Serializable
private data class ReportVerdictDto(
    val verdict: String = "",
    val lessons: List<String> = emptyList(),
)
