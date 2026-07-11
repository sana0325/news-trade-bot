package com.scalpbot.bingx.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scalpbot.bingx.ScalpBotApp
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import java.util.concurrent.TimeUnit

private const val TAG = "PairsRefreshWorker"
private const val PERIODIC_WORK_NAME = "scalpbot_pairs_refresh"
private const val ONE_TIME_WORK_NAME = "scalpbot_pairs_refresh_once"
private const val TOP_PAIRS_COUNT = 25

/**
 * 25 найліквідніших USDT-perp пар за 24h обсягом — тягнеться динамічно при
 * старті й раз на добу. `enabled`-стан уже наявних пар зберігається, щоб
 * користувацькі вимкнення в Налаштуваннях не скидались щодня.
 */
class PairsRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = (applicationContext as ScalpBotApp).serviceLocator
        val rest = locator.bingXRestClient
        val pairDao = locator.database.pairDao()

        val contracts = rest.getContracts().getOrElse {
            AppLogger.w(TAG, "Не вдалось оновити список контрактів", it)
            return Result.retry()
        }
        rest.getAllTickers24hRaw().onSuccess { raw ->
            AppLogger.d(TAG, "RAW ticker JSON (перші 1500 символів): ${raw.take(1500)}")
        }

        val tickers = rest.getAllTickers24h().getOrElse {
            AppLogger.w(TAG, "Не вдалось оновити 24h тикери", it)
            return Result.retry()
        }.associateBy { it.symbol }

        val existingEnabled = pairDao.getAll().associate { it.symbol to it.enabled }

        val ranked = contracts
            // BingX contracts endpoint also lists non-crypto CFD-style instruments
            // (gold/silver/index tokens like "NCCOGOLD2USD") that also settle in
            // USDT but don't follow the "<COIN>-USDT" symbol convention — excluded
            // explicitly, since currency == "USDT" alone isn't a strict enough filter.
            .filter { it.currency == "USDT" && it.status == 1 && it.symbol.endsWith("-USDT") }
            .mapNotNull { contract ->
                val ticker = tickers[contract.symbol] ?: return@mapNotNull null
                contract to ticker
            }
            .sortedByDescending { (_, ticker) -> ticker.quoteVolume }
            .take(TOP_PAIRS_COUNT)

        val now = System.currentTimeMillis()
        val entities = ranked.mapIndexed { index, (contract, ticker) ->
            PairCacheEntity(
                symbol = contract.symbol,
                baseAsset = contract.asset ?: contract.symbol.substringBefore("-"),
                quoteAsset = contract.currency ?: "USDT",
                volume24h = ticker.quoteVolume,
                priceChangePercent24h = ticker.priceChangePercent,
                tickSize = contract.effectiveTickSize(),
                stepSize = contract.effectiveStepSize(),
                minQty = contract.effectiveMinQty(),
                pricePrecision = contract.pricePrecision,
                quantityPrecision = contract.quantityPrecision,
                enabled = existingEnabled[contract.symbol] ?: true,
                rank = index,
                lastUpdatedEpochMs = now,
            )
        }

        if (entities.isEmpty()) {
            AppLogger.w(TAG, "Порожній список пар після фільтрації — залишаю попередній кеш")
            return Result.retry()
        }

        pairDao.upsertAll(entities)
        AppLogger.i(TAG, "Оновлено ${entities.size} пар")
        return Result.success()
    }

    companion object {
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<PairsRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PairsRefreshWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
