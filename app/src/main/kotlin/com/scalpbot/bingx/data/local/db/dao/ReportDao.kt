package com.scalpbot.bingx.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.scalpbot.bingx.data.local.db.entity.ReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReportDao {

    @Insert
    suspend fun insert(report: ReportEntity): Long

    @Query("SELECT * FROM reports ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getLatest(): ReportEntity?
}
