package com.scalpbot.bingx.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TradeDirection { LONG, SHORT }

enum class TradeStatus { OPEN, CLOSED }

enum class CloseReason { TAKE_PROFIT, STOP_LOSS, TIMEOUT, KILL_SWITCH, MANUAL }

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val direction: TradeDirection,
    val status: TradeStatus,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long?,
    val entryPrice: Double,
    val slPrice: Double,
    val tpPrice: Double,
    val exitPrice: Double?,
    val leverage: Int,
    val marginUsd: Double,
    val quantity: Double,
    val pnlUsd: Double?,
    val pnlPercent: Double?,
    val aiReason: String,
    val aiConfidence: Double,
    /** JSON-знімок ринкового контексту (свічки M5/M15, спред, funding) на момент входу. */
    val marketContextJson: String,
    val closeReason: CloseReason?,
    /** Версія "уроків", активна на момент прийняття рішення. */
    val lessonsVersion: Int?,
    /** Ідентифікатор дводенного звіту, у якому цю угоду вже розібрали (null, поки не розібрана). */
    val analyzedInReportId: Long? = null,
    /** ATR(14) на M5 у % від ціни на момент входу — база, від якої рахувались SL/TP. */
    val atrPercentAtEntry: Double? = null,
    /** Тривалість угоди в секундах, рахується при закритті (closedAtEpochMs - openedAtEpochMs). */
    val durationSeconds: Long? = null,
)
