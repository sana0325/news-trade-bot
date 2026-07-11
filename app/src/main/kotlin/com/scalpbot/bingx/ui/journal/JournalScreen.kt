package com.scalpbot.bingx.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.data.local.db.entity.ReportEntity
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.local.db.entity.TradeStatus
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.ProfitGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(viewModel: JournalViewModel = viewModel()) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Угоди", "Звіти")

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tabIndex == index, onClick = { tabIndex = index }, text = { Text(title) })
                }
            }
            when (tabIndex) {
                0 -> TradesTab(viewModel)
                else -> ReportsTab(viewModel)
            }
        }
    }
}

@Composable
private fun TradesTab(viewModel: JournalViewModel) {
    val trades by viewModel.filteredTrades.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val symbols by viewModel.availableSymbols.collectAsStateWithLifecycle()
    var selectedTrade by remember { mutableStateOf<TradeEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter.result == ResultFilter.ALL,
                onClick = { viewModel.setResultFilter(ResultFilter.ALL) },
                label = { Text("Усі") },
            )
            FilterChip(
                selected = filter.result == ResultFilter.PROFIT,
                onClick = { viewModel.setResultFilter(ResultFilter.PROFIT) },
                label = { Text("Прибуткові") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ProfitGreen.copy(alpha = 0.25f)),
            )
            FilterChip(
                selected = filter.result == ResultFilter.LOSS,
                onClick = { viewModel.setResultFilter(ResultFilter.LOSS) },
                label = { Text("Збиткові") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LossRed.copy(alpha = 0.25f)),
            )
        }
        if (symbols.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter.symbol == null, onClick = { viewModel.setSymbolFilter(null) }, label = { Text("Усі пари") })
                symbols.forEach { symbol ->
                    FilterChip(
                        selected = filter.symbol == symbol,
                        onClick = { viewModel.setSymbolFilter(symbol) },
                        label = { Text(symbol) },
                    )
                }
            }
        }

        if (trades.isEmpty()) {
            Text(
                text = "Угод поки немає",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(trades, key = { it.id }) { trade ->
                    TradeRow(trade = trade, onClick = { selectedTrade = trade })
                    Spacer(modifier = Modifier.padding(4.dp))
                }
            }
        }
    }

    selectedTrade?.let { trade ->
        TradeDetailDialog(trade = trade, onDismiss = { selectedTrade = null })
    }
}

@Composable
private fun TradeRow(trade: TradeEntity, onClick: () -> Unit) {
    val pnl = trade.pnlUsd
    val pnlColor = when {
        pnl == null -> MaterialTheme.colorScheme.onSurfaceVariant
        pnl >= 0 -> ProfitGreen
        else -> LossRed
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = "${trade.symbol} · ${directionLabel(trade.direction)}", fontWeight = FontWeight.Bold)
                Text(
                    text = formatDate(trade.openedAtEpochMs) + (trade.closeReason?.let { " · ${closeReasonLabel(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when {
                    trade.status == TradeStatus.OPEN -> "Відкрито"
                    pnl == null -> "—"
                    else -> "${if (pnl >= 0) "+" else ""}${"%.2f".format(Locale.US, pnl)}$"
                },
                color = pnlColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TradeDetailDialog(trade: TradeEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${trade.symbol} · ${directionLabel(trade.direction)}") },
        text = {
            Column {
                DetailLine("Вхід", trade.entryPrice.toString())
                DetailLine("SL", trade.slPrice.toString())
                DetailLine("TP", trade.tpPrice.toString())
                trade.exitPrice?.let { DetailLine("Вихід", it.toString()) }
                DetailLine("Плече", "${trade.leverage}x")
                DetailLine("Маржа", "${"%.2f".format(Locale.US, trade.marginUsd)}$")
                trade.pnlPercent?.let { DetailLine("PnL", "${"%.2f".format(Locale.US, it)}% (${"%.2f".format(Locale.US, trade.pnlUsd ?: 0.0)}$)") }
                trade.closeReason?.let { DetailLine("Причина закриття", closeReasonLabel(it)) }
                Text(
                    text = "Обґрунтування ШІ",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Text(text = trade.aiReason.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрити") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReportsTab(viewModel: JournalViewModel) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()

    if (reports.isEmpty()) {
        Text(text = "Дводенних звітів поки немає", modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(contentPadding = PaddingValues(12.dp)) {
        items(reports, key = { it.id }) { report ->
            ReportCard(report)
            Spacer(modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun ReportCard(report: ReportEntity) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "${formatDate(report.periodStartEpochMs)} — ${formatDate(report.periodEndEpochMs)}",
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Угод у періоді: ${report.tradeCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = report.verdictText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun directionLabel(direction: TradeDirection) = if (direction == TradeDirection.LONG) "Лонг" else "Шорт"

private fun closeReasonLabel(reason: CloseReason): String = when (reason) {
    CloseReason.TAKE_PROFIT -> "TP"
    CloseReason.STOP_LOSS -> "SL"
    CloseReason.TIMEOUT -> "Тайм-аут"
    CloseReason.KILL_SWITCH -> "Kill-switch"
    CloseReason.MANUAL -> "Вручну"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(epochMs))
