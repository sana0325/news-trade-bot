package com.scalpbot.bingx.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val createdAtEpochMs: Long,
    /** JSON зі статистикою за період: winrate, profit factor, avgPnl, найкращі/найгірші пари, long vs short, серії збитків. */
    val statsJson: String,
    /** Текстовий вердикт від DeepSeek українською. */
    val verdictText: String,
    /** Версія "уроків", яку цей звіт породив (null, якщо DeepSeek не запропонував змін). */
    val producedLessonsVersion: Int?,
    val tradeCount: Int,
)
