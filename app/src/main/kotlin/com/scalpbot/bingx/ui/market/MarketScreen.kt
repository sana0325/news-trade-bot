package com.scalpbot.bingx.ui.market

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.domain.model.PairTicker
import com.scalpbot.bingx.ui.common.Sparkline
import com.scalpbot.bingx.ui.common.ComingSoonScreen
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.ProfitGreen
import java.util.Locale

@Composable
fun MarketScreen(onPairClick: (String) -> Unit, viewModel: MarketViewModel = viewModel()) {
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureStarted() }

    if (tickers.isEmpty()) {
        ComingSoonScreen(title = "Ринок завантажується")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(tickers, key = { it.symbol }) { ticker ->
            PairCard(ticker = ticker, onClick = { onPairClick(ticker.symbol) })
        }
    }
}

@Composable
private fun PairCard(ticker: PairTicker, onClick: () -> Unit) {
    val changeColor = if (ticker.priceChangePercent24h >= 0) ProfitGreen else LossRed
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = ticker.baseAsset, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                text = formatPrice(ticker.lastPrice),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatSignedPercent(ticker.priceChangePercent24h),
                style = MaterialTheme.typography.labelSmall,
                color = changeColor,
                fontWeight = FontWeight.Bold,
            )
            Sparkline(
                values = ticker.sparkline,
                color = changeColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
            )
        }
    }
}

private fun formatPrice(price: Double): String = if (price >= 1.0) {
    String.format(Locale.US, "%.2f", price)
} else {
    String.format(Locale.US, "%.6f", price)
}

private fun formatSignedPercent(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "$sign${String.format(Locale.US, "%.2f", value)}%"
}
