package com.scalpbot.bingx.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.scalpbot.bingx.data.local.db.entity.LessonEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LessonDao {

    @Insert
    suspend fun insert(lesson: LessonEntity): Long

    @Query("SELECT * FROM lessons ORDER BY version DESC")
    fun observeAll(): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): LessonEntity?

    @Query("SELECT MAX(version) FROM lessons")
    suspend fun getMaxVersion(): Int?

    @Query("UPDATE lessons SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE lessons SET isActive = 1 WHERE version = :version")
    suspend fun activateVersion(version: Int)

    @Transaction
    suspend fun setActiveVersion(version: Int) {
        deactivateAll()
        activateVersion(version)
    }

    @Transaction
    suspend fun insertAsActive(lesson: LessonEntity): Long {
        deactivateAll()
        return insert(lesson.copy(isActive = true))
    }
}
