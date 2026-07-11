package com.scalpbot.bingx.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pairs")
data class PairCacheEntity(
    @PrimaryKey val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val volume24h: Double,
    val priceChangePercent24h: Double,
    val tickSize: Double,
    val stepSize: Double,
    val minQty: Double,
    val pricePrecision: Int,
    val quantityPrecision: Int,
    val enabled: Boolean,
    val rank: Int,
    val lastUpdatedEpochMs: Long,
)
