package com.scalpbot.bingx.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.data.local.prefs.TradingMode
import com.scalpbot.bingx.domain.model.EngineStatus
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.ProfitGreen
import com.scalpbot.bingx.ui.theme.WarningAmber
import java.time.LocalTime
import java.util.Locale

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.engineState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedGreeting()
            StatusCard(status = state.status, mode = state.mode)
            BalanceCard(equityUsd = state.equityUsd, pnlTodayUsd = state.pnlTodayUsd, pnlTodayPercent = state.pnlTodayPercent)
            Spacer(modifier = Modifier.height(8.dp))
            PrimaryActionButton(status = state.status, onClick = viewModel::onPrimaryActionClick)
        }
    }
}

@Composable
private fun AnimatedGreeting() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -it / 3 },
    ) {
        Column {
            Text(text = "Привіт 👋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text = timeOfDayGreeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun timeOfDayGreeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Доброго ранку — двигун готовий до роботи"
    in 11..17 -> "Доброго дня — ринок чекає"
    in 18..22 -> "Доброго вечора — час перевірити позиції"
    else -> "Доброї ночі — бот пильнує ринок за тебе"
}

@Composable
private fun StatusCard(status: EngineStatus, mode: TradingMode) {
    val (color, label) = when (status) {
        EngineStatus.RUNNING -> ProfitGreen to "Онлайн"
        EngineStatus.PAUSED -> WarningAmber to "Пауза"
        EngineStatus.KILL_SWITCHED -> LossRed to "Kill-switch"
        EngineStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant to "Зупинено"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape),
            )
            Text(text = label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
            Spacer(modifier = Modifier.weight(1f))
            ModeBadge(mode)
        }
    }
}

@Composable
private fun ModeBadge(mode: TradingMode) {
    val color = if (mode == TradingMode.LIVE) LossRed else ProfitGreen
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
        Text(
            text = mode.name,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BalanceCard(equityUsd: Double, pnlTodayUsd: Double, pnlTodayPercent: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(text = "Баланс", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "$" + String.format(Locale.US, "%.2f", equityUsd),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val pnlColor = if (pnlTodayUsd >= 0) ProfitGreen else LossRed
            val sign = if (pnlTodayUsd >= 0) "+" else ""
            Text(
                text = "PnL сьогодні: $sign${String.format(Locale.US, "%.2f", pnlTodayUsd)}$ ($sign${String.format(Locale.US, "%.2f", pnlTodayPercent)}%)",
                color = pnlColor,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(status: EngineStatus, onClick: () -> Unit) {
    val label = when (status) {
        EngineStatus.STOPPED -> "Старт"
        EngineStatus.RUNNING -> "Пауза"
        EngineStatus.PAUSED -> "Продовжити"
        EngineStatus.KILL_SWITCHED -> "Заблоковано"
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}
