package com.scalpbot.bingx.ui.market

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.ui.common.CandlestickChart
import com.scalpbot.bingx.ui.common.ChartMarker
import com.scalpbot.bingx.ui.theme.AccentGreen
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.ProfitGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDetailScreen(symbol: String, onBack: () -> Unit, viewModel: MarketDetailViewModel = viewModel()) {
    LaunchedEffect(symbol) { viewModel.load(symbol) }

    val chartState by viewModel.chartState.collectAsStateWithLifecycle()
    val trades by viewModel.trades.collectAsStateWithLifecycle()

    val markers = trades.flatMap { trade ->
        buildList {
            add(ChartMarker(trade.openedAtEpochMs, trade.entryPrice, AccentGreen, isEntry = true))
            val exitPrice = trade.exitPrice
            val closedAt = trade.closedAtEpochMs
            if (exitPrice != null && closedAt != null) {
                val profit = (trade.pnlUsd ?: 0.0) >= 0
                add(ChartMarker(closedAt, exitPrice, if (profit) ProfitGreen else LossRed, isEntry = false))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(symbol) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (val state = chartState) {
                is ChartLoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                    Text(
                        text = "Завантаження графіка…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is ChartLoadState.Error -> {
                    Text(
                        text = "Не вдалось завантажити графік: ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LossRed,
                    )
                    Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Спробувати ще раз")
                    }
                }
                is ChartLoadState.Loaded -> {
                    CandlestickChart(
                        candles = state.candles,
                        markers = markers,
                        bullColor = ProfitGreen,
                        bearColor = LossRed,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}
