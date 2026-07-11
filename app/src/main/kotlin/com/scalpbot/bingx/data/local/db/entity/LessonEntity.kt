package com.scalpbot.bingx.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val version: Int,
    val createdAtEpochMs: Long,
    /** JSON-масив коротких корективів до торгового промта, згенерований DeepSeek. */
    val contentJson: String,
    /** Джерело: id звіту, що породив цю версію (null для версії 0 — порожні уроки). */
    val sourceReportId: Long?,
    val isActive: Boolean,
)
