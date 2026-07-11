package com.scalpbot.bingx.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.local.db.entity.TradeStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {

    @Insert
    suspend fun insert(trade: TradeEntity): Long

    @Update
    suspend fun update(trade: TradeEntity)

    @Query("SELECT * FROM trades WHERE status = :status LIMIT 1")
    suspend fun getOpenTrade(status: TradeStatus = TradeStatus.OPEN): TradeEntity?

    @Query("SELECT * FROM trades WHERE status = :status LIMIT 1")
    fun observeOpenTrade(status: TradeStatus = TradeStatus.OPEN): Flow<TradeEntity?>

    @Query("SELECT * FROM trades ORDER BY openedAtEpochMs DESC")
    fun observeAll(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades WHERE status = 'CLOSED' ORDER BY closedAtEpochMs DESC LIMIT :limit")
    suspend fun getRecentClosed(limit: Int): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE openedAtEpochMs >= :fromEpochMs")
    suspend fun getSince(fromEpochMs: Long): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE analyzedInReportId IS NULL AND status = 'CLOSED' AND closedAtEpochMs <= :untilEpochMs")
    suspend fun getUnanalyzedClosedUntil(untilEpochMs: Long): List<TradeEntity>

    @Query("UPDATE trades SET analyzedInReportId = :reportId WHERE id IN (:tradeIds)")
    suspend fun markAnalyzed(tradeIds: List<Long>, reportId: Long)

    @Query("SELECT COUNT(*) FROM trades WHERE openedAtEpochMs >= :sinceEpochMs")
    suspend fun countSince(sinceEpochMs: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT closeReason FROM trades
            WHERE status = 'CLOSED'
            ORDER BY closedAtEpochMs DESC
            LIMIT :lastN
        ) WHERE closeReason = 'STOP_LOSS'
        """
    )
    suspend fun countLastLosses(lastN: Int): Int
}
