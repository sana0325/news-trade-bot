package com.scalpbot.bingx.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.ui.common.ComingSoonScreen
import com.scalpbot.bingx.ui.common.Sparkline
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.ProfitGreen
import java.util.Locale
import kotlin.math.abs

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = viewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()

    if (summary.closedTradeCount == 0) {
        ComingSoonScreen(title = "Статистика зʼявиться після перших закритих угод")
        return
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EquityCurveCard(summary.equityCurve, summary.totalPnlUsd) }
            item { StatTilesRow(summary) }
            item { LongVsShortCard(summary) }
            item { Text("PnL по парах", style = MaterialTheme.typography.titleMedium) }
            items(summary.pnlByPair, key = { it.first }) { (symbol, pnl) ->
                PnlBarRow(symbol, pnl, summary.pnlByPair.maxOfOrNull { abs(it.second) } ?: 1.0)
            }
        }
    }
}

@Composable
private fun EquityCurveCard(equityCurve: List<Double>, totalPnlUsd: Double) {
    val color = if (totalPnlUsd >= 0) ProfitGreen else LossRed
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Equity-крива", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${if (totalPnlUsd >= 0) "+" else ""}${String.format(Locale.US, "%.2f", totalPnlUsd)}$",
                style = MaterialTheme.typography.headlineSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Sparkline(
                values = equityCurve,
                color = color,
                modifier = Modifier.fillMaxWidth().height(80.dp).padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StatTilesRow(summary: StatsSummary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = "Winrate",
            value = "${String.format(Locale.US, "%.1f", summary.winratePercent)}%",
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Profit factor",
            value = if (summary.profitFactor.isInfinite()) "∞" else String.format(Locale.US, "%.2f", summary.profitFactor),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = "Сер. PnL",
            value = "${String.format(Locale.US, "%.2f", summary.avgPnlUsd)}$",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LongVsShortCard(summary: StatsSummary) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Лонг vs Шорт", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(text = "Лонг (${summary.longCount})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${if (summary.longPnlUsd >= 0) "+" else ""}${String.format(Locale.US, "%.2f", summary.longPnlUsd)}$",
                        color = if (summary.longPnlUsd >= 0) ProfitGreen else LossRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column {
                    Text(text = "Шорт (${summary.shortCount})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${if (summary.shortPnlUsd >= 0) "+" else ""}${String.format(Locale.US, "%.2f", summary.shortPnlUsd)}$",
                        color = if (summary.shortPnlUsd >= 0) ProfitGreen else LossRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PnlBarRow(symbol: String, pnlUsd: Double, maxAbsPnl: Double) {
    val color = if (pnlUsd >= 0) ProfitGreen else LossRed
    val fraction = (abs(pnlUsd) / maxAbsPnl).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = symbol, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${if (pnlUsd >= 0) "+" else ""}${String.format(Locale.US, "%.2f", pnlUsd)}$",
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .padding(top = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
    }
}
