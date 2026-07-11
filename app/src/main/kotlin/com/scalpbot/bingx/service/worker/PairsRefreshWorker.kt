package com.scalpbot.bingx.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import java.util.concurrent.TimeUnit

private const val TAG = "PairsRefreshWorker"
private const val PERIODIC_WORK_NAME = "scalpbot_pairs_refresh"
private const val ONE_TIME_WORK_NAME = "scalpbot_pairs_refresh_once"
private const val TOP_PAIRS_COUNT = 25
private const val MAX_24H_MOVE_PERCENT = 20.0

/**
 * 25 найліквідніших USDT-perp пар за 24h обсягом — тягнеться динамічно при
 * старті й раз на добу. `enabled`-стан уже наявних пар зберігається, щоб
 * користувацькі вимкнення в Налаштуваннях не скидались щодня.
 */
class PairsRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val locator = (applicationContext as ShiScalpBotApp).serviceLocator
        val rest = locator.bingXRestClient
        val pairDao = locator.database.pairDao()

        val contracts = rest.getContracts().getOrElse {
            AppLogger.w(TAG, "Не вдалось оновити список контрактів", it)
            return Result.retry()
        }

        val tickers = rest.getAllTickers24h().getOrElse {
            AppLogger.w(TAG, "Не вдалось оновити 24h тикери", it)
            return Result.retry()
        }.associateBy { it.symbol }

        val existingEnabled = pairDao.getAll().associate { it.symbol to it.enabled }

        val ranked = contracts
            // BingX contracts endpoint also lists non-crypto CFD-style instruments
            // (gold/silver/index/commodity tokens: NCCOGOLD2USD-USDT,
            // NCSINASDAQ1002USD-USDT, NCCOXAG2USD-USDT, NCCO1OILWTI2USD-USDT...) that
            // DO end in "-USDT" too and often report inflated notional volume (BTC-
            // and ETH-tier), so the "-USDT" suffix alone doesn't exclude them. Every
            // one of them observed so far shares the "NC" symbol prefix, which real
            // crypto tickers don't use.
            .filter {
                it.currency == "USDT" && it.status == 1 &&
                    it.symbol.endsWith("-USDT") && !it.symbol.startsWith("NC")
            }
            .mapNotNull { contract ->
                val ticker = tickers[contract.symbol] ?: return@mapNotNull null
                // Різкі 24h-стрибки (пампи мікрокапів на кшталт CASHCAT/OWL/ANSEM)
                // часто мають роздутий разовий обсяг, який тимчасово підкидає їх у
                // топ за quoteVolume, і саме такі інструменти BingX найчастіше сам
                // тимчасово блокує для API-ордерів через захист від ліквідацій —
                // відсіюємо їх зі старту, а не постфактум через помилки ордерів.
                if (kotlin.math.abs(ticker.priceChangePercent) > MAX_24H_MOVE_PERCENT) return@mapNotNull null
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
