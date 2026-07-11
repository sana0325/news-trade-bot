package com.scalpbot.bingx.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.scalpbot.bingx.domain.model.Candle
import kotlin.math.max
import kotlin.math.min

data class ChartMarker(
    val timeMs: Long,
    val price: Double,
    val color: Color,
    val isEntry: Boolean,
)

/**
 * Свічковий графік на чистому Compose Canvas (без сторонньої бібліотеки
 * графіків) — з мітками входів/виходів бота поверх свічок.
 */
@Composable
fun CandlestickChart(
    candles: List<Candle>,
    markers: List<ChartMarker>,
    bullColor: Color,
    bearColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas

        val priceMin = candles.minOf { it.low }
        val priceMax = candles.maxOf { it.high }
        val priceRange = (priceMax - priceMin).let { if (it > 0.0) it else 1.0 }

        val slotWidth = size.width / candles.size
        val bodyWidth = slotWidth * 0.6f

        fun yFor(price: Double): Float =
            size.height - ((price - priceMin) / priceRange * size.height).toFloat()

        candles.forEachIndexed { index, candle ->
            val centerX = slotWidth * index + slotWidth / 2f
            val isBull = candle.close >= candle.open
            val color = if (isBull) bullColor else bearColor

            drawLine(
                color = color,
                start = Offset(centerX, yFor(candle.high)),
                end = Offset(centerX, yFor(candle.low)),
                strokeWidth = 1.5.dp.toPx(),
            )

            val bodyTop = yFor(max(candle.open, candle.close))
            val bodyBottom = yFor(min(candle.open, candle.close))
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, (bodyBottom - bodyTop).coerceAtLeast(1.5f)),
            )
        }

        markers.forEach { marker ->
            val index = candles.indexOfLast { it.openTimeMs <= marker.timeMs }.let { if (it < 0) 0 else it }
            val centerX = slotWidth * index + slotWidth / 2f
            val y = yFor(marker.price)
            drawCircle(color = marker.color, radius = 5.dp.toPx(), center = Offset(centerX, y))
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(centerX, y),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}
