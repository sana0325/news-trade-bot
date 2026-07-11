package com.scalpbot.bingx.data.local.db

import androidx.room.TypeConverter
import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeStatus

class Converters {

    @TypeConverter
    fun fromTradeDirection(value: TradeDirection): String = value.name

    @TypeConverter
    fun toTradeDirection(value: String): TradeDirection = TradeDirection.valueOf(value)

    @TypeConverter
    fun fromTradeStatus(value: TradeStatus): String = value.name

    @TypeConverter
    fun toTradeStatus(value: String): TradeStatus = TradeStatus.valueOf(value)

    @TypeConverter
    fun fromCloseReason(value: CloseReason?): String? = value?.name

    @TypeConverter
    fun toCloseReason(value: String?): CloseReason? = value?.let { CloseReason.valueOf(it) }
}
