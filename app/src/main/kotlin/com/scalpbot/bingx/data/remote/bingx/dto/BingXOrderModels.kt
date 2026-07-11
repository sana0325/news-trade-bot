package com.scalpbot.bingx.data.remote.bingx.dto

import kotlinx.serialization.Serializable

enum class OrderSide { BUY, SELL }

/** BOTH — one-way режим акаунта; LONG/SHORT — hedge-режим. */
enum class PositionSide { BOTH, LONG, SHORT }

@Serializable
data class TpSlSpec(
    val type: String,
    val stopPrice: Double,
    val workingType: String = "MARK_PRICE",
)

data class NewOrderRequest(
    val symbol: String,
    val side: OrderSide,
    val positionSide: PositionSide,
    val quantity: Double,
    val stopLoss: TpSlSpec,
    val takeProfit: TpSlSpec,
)
