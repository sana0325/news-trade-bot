package com.scalpbot.bingx.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(pairs: List<PairCacheEntity>)

    @Query("SELECT * FROM pairs ORDER BY rank ASC")
    fun observeAll(): Flow<List<PairCacheEntity>>

    @Query("SELECT * FROM pairs WHERE enabled = 1 ORDER BY rank ASC")
    suspend fun getEnabled(): List<PairCacheEntity>

    @Query("SELECT symbol FROM pairs WHERE enabled = 1")
    suspend fun getEnabledSymbols(): List<String>

    @Query("UPDATE pairs SET enabled = :enabled WHERE symbol = :symbol")
    suspend fun setEnabled(symbol: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM pairs")
    suspend fun count(): Int
}
