package com.scalpbot.bingx.data.remote.bingx.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BingX Swap V2 огортає всі REST-відповіді в {code, msg, data}. Поля нижче —
 * найкраще наближення до офіційної документації BingX Perpetual Futures на
 * момент написання; перед продакшн-використанням звірте з актуальним API-довідником
 * BingX (можливі перейменування полів між версіями v1/v2/v3).
 */
@Serializable
data class BingXEnvelope<T>(
    val code: Int = 0,
    val msg: String? = null,
    val data: T? = null,
)

@Serializable
data class ServerTimeData(val serverTime: Long)

@Serializable
data class ContractDto(
    val symbol: String,
    val asset: String? = null,
    val currency: String? = null,
    val pricePrecision: Int = 4,
    val quantityPrecision: Int = 3,
    val tradeMinQuantity: Double? = null,
    val tradeMinUSDT: Double? = null,
    val tickSize: Double? = null,
    val stepSize: Double? = null,
    val status: Int = 1,
) {
    fun effectiveTickSize(): Double = tickSize ?: Math.pow(10.0, -pricePrecision.toDouble())
    fun effectiveStepSize(): Double = stepSize ?: Math.pow(10.0, -quantityPrecision.toDouble())
    fun effectiveMinQty(): Double = tradeMinQuantity ?: effectiveStepSize()
}

@Serializable
data class Ticker24hDto(
    val symbol: String,
    val lastPrice: Double = 0.0,
    val priceChangePercent: Double = 0.0,
    val volume: Double = 0.0,
    val quoteVolume: Double = 0.0,
    val highPrice: Double = 0.0,
    val lowPrice: Double = 0.0,
)

@Serializable
data class KlineDto(
    @SerialName("time") val openTimeMs: Long = 0L,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val close: Double = 0.0,
    val volume: Double = 0.0,
)

@Serializable
data class BookTickerDto(
    val symbol: String,
    val bidPrice: Double = 0.0,
    val askPrice: Double = 0.0,
) {
    fun spreadPercent(): Double {
        val mid = (bidPrice + askPrice) / 2.0
        if (mid <= 0.0) return 0.0
        return (askPrice - bidPrice) / mid * 100.0
    }
}

@Serializable
data class PremiumIndexDto(
    val symbol: String,
    val markPrice: Double = 0.0,
    val lastFundingRate: Double = 0.0,
    val nextFundingTime: Long = 0,
)

@Serializable
data class BalanceDto(
    val asset: String = "USDT",
    val balance: Double = 0.0,
    val equity: Double = 0.0,
    val availableMargin: Double = 0.0,
    val usedMargin: Double = 0.0,
    val unrealizedProfit: Double = 0.0,
)

@Serializable
data class BalanceEnvelopeData(val balance: BalanceDto)

@Serializable
data class PositionDto(
    val symbol: String,
    val positionSide: String = "BOTH",
    val positionAmt: Double = 0.0,
    val avgPrice: Double = 0.0,
    val unrealizedProfit: Double = 0.0,
    val leverage: Int = 1,
    val markPrice: Double = 0.0,
)

@Serializable
data class PositionModeDto(@SerialName("dualSidePosition") val dualSidePosition: Boolean = false)

@Serializable
data class OrderResponseData(val order: OrderResultDto)

@Serializable
data class OrderResultDto(
    val symbol: String,
    val orderId: Long = 0,
    val side: String = "",
    val positionSide: String = "",
    val type: String = "",
    val status: String = "",
    val price: Double = 0.0,
    val origQty: Double = 0.0,
)

@Serializable
data class ListenKeyData(val listenKey: String)

@Serializable
data class CloseAllPositionsData(val success: List<String> = emptyList(), val failed: List<String> = emptyList())
