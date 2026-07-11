package com.scalpbot.bingx.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.PairCacheEntity
import com.scalpbot.bingx.data.local.prefs.RiskPreset
import com.scalpbot.bingx.data.local.prefs.TradingMode
import com.scalpbot.bingx.ui.theme.LossRed
import com.scalpbot.bingx.ui.theme.WarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onOpenLogs: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    var showLiveConfirm by remember { mutableStateOf(false) }
    var showAggressiveConfirm by remember { mutableStateOf(false) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SectionCard(title = "Режим торгівлі") {
                    ModeSelector(
                        mode = state.tradingMode,
                        onSelectDemo = { viewModel.setTradingMode(TradingMode.DEMO) },
                        onSelectLive = { showLiveConfirm = true },
                    )
                }
            }
            item {
                SectionCard(title = "API-ключі") {
                    ApiKeysSection(state, viewModel)
                }
            }
            item {
                SectionCard(title = "Ризик") {
                    RiskSection(
                        state = state,
                        onSelectConservative = { viewModel.setRiskPreset(RiskPreset.CONSERVATIVE) },
                        onSelectAggressive = { showAggressiveConfirm = true },
                        onCustomChange = { margin, leverage -> viewModel.setCustomRisk(margin, leverage) },
                    )
                }
            }
            item {
                SectionCard(title = "Параметри") {
                    LimitsSection(state, viewModel)
                }
            }
            item {
                SectionCard(title = "Пари (${pairs.count { it.enabled }}/${pairs.size})") {
                    PairsSection(pairs, viewModel::setPairEnabled)
                }
            }
            item {
                SectionCard(title = "Керування") {
                    ControlsSection(state, viewModel, onOpenLogs)
                }
            }
        }
    }

    if (showLiveConfirm) {
        AlertDialog(
            onDismissRequest = { showLiveConfirm = false },
            title = { Text("Перейти на LIVE?") },
            text = { Text("Це реальний акаунт BingX — угоди виконуватимуться на справжні кошти. Переконайтесь, що ключі й ризик-налаштування коректні.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTradingMode(TradingMode.LIVE)
                    showLiveConfirm = false
                }) { Text("Так, перейти на LIVE") }
            },
            dismissButton = { TextButton(onClick = { showLiveConfirm = false }) { Text("Скасувати") } },
        )
    }

    if (showAggressiveConfirm) {
        AlertDialog(
            onDismissRequest = { showAggressiveConfirm = false },
            title = { Text("Увімкнути Aggressive?") },
            text = { Text("50% депозиту на угоду з плечем 20x — висока ймовірність ліквідації при різкому русі ціни проти позиції. Використовуйте свідомо.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRiskPreset(RiskPreset.AGGRESSIVE)
                    showAggressiveConfirm = false
                }) { Text("Розумію ризик, увімкнути") }
            },
            dismissButton = { TextButton(onClick = { showAggressiveConfirm = false }) { Text("Скасувати") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

@Composable
private fun ModeSelector(mode: TradingMode, onSelectDemo: () -> Unit, onSelectLive: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSelectDemo, enabled = mode != TradingMode.DEMO) { Text("DEMO") }
        Button(onClick = onSelectLive, enabled = mode != TradingMode.LIVE) { Text("LIVE") }
    }
}

@Composable
private fun ApiKeysSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var demoKey by remember { mutableStateOf(state.bingxDemoApiKey) }
    var demoSecret by remember { mutableStateOf(state.bingxDemoApiSecret) }
    var liveKey by remember { mutableStateOf(state.bingxLiveApiKey) }
    var liveSecret by remember { mutableStateOf(state.bingxLiveApiSecret) }
    var deepseekKey by remember { mutableStateOf(state.deepseekApiKey) }

    Text(text = "BingX DEMO (VST)", style = MaterialTheme.typography.labelLarge)
    LabeledSecretField("API Key", demoKey) { demoKey = it; viewModel.setBingxDemoKeys(demoKey, demoSecret) }
    LabeledSecretField("API Secret", demoSecret) { demoSecret = it; viewModel.setBingxDemoKeys(demoKey, demoSecret) }

    Text(text = "BingX LIVE", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    LabeledSecretField("API Key", liveKey) { liveKey = it; viewModel.setBingxLiveKeys(liveKey, liveSecret) }
    LabeledSecretField("API Secret", liveSecret) { liveSecret = it; viewModel.setBingxLiveKeys(liveKey, liveSecret) }

    Text(text = "DeepSeek", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    LabeledSecretField("API Key", deepseekKey) { deepseekKey = it; viewModel.setDeepSeekKey(deepseekKey) }

    Text(
        text = "Права ключа BingX: лише торгівля. Вивід коштів вимикайте — додаток ним не користується.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LabeledSecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun RiskSection(
    state: SettingsUiState,
    onSelectConservative: () -> Unit,
    onSelectAggressive: () -> Unit,
    onCustomChange: (Float, Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onSelectConservative, enabled = state.riskPreset != RiskPreset.CONSERVATIVE) {
            Text("Conservative (5% / 5x)")
        }
        OutlinedButton(onClick = onSelectAggressive, enabled = state.riskPreset != RiskPreset.AGGRESSIVE) {
            Text("Aggressive (50% / 20x)")
        }
    }
    Text(
        text = "Поточне: маржа ${state.marginPercent}% депозиту, плече ${state.leverage}x",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )

    var customMargin by remember(state.riskPreset) { mutableStateOf(state.marginPercent.toString()) }
    var customLeverage by remember(state.riskPreset) { mutableStateOf(state.leverage.toString()) }
    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = customMargin,
            onValueChange = {
                customMargin = it
                val margin = it.toFloatOrNull()
                val leverage = customLeverage.toIntOrNull()
                if (margin != null && leverage != null) onCustomChange(margin, leverage)
            },
            label = { Text("Маржа %") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = customLeverage,
            onValueChange = {
                customLeverage = it
                val margin = customMargin.toFloatOrNull()
                val leverage = it.toIntOrNull()
                if (margin != null && leverage != null) onCustomChange(margin, leverage)
            },
            label = { Text("Плече x") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LimitsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var dailyLimit by remember { mutableStateOf(state.dailyLossLimitPercent.toString()) }
    var maxTrades by remember { mutableStateOf(state.maxTradesPerDay.toString()) }

    NumberField("Денний ліміт збитку (%)", dailyLimit) {
        dailyLimit = it
        it.toFloatOrNull()?.let(viewModel::setDailyLossLimitPercent)
    }
    NumberField("Ліміт угод на день", maxTrades) {
        maxTrades = it
        it.toIntOrNull()?.let(viewModel::setMaxTradesPerDay)
    }
    Text(
        text = "Kill-switch (-30% від старту), cooldown після 3 стопів поспіль і тайм-аут " +
            "позиції (макс. 4 год або закриття достроково, якщо за 30 M5-свічок ціна не " +
            "пройшла хоча б 1×ATR у бік TP) — хардкод, з UI не змінюються.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun PairsSection(pairs: List<PairCacheEntity>, onToggle: (String, Boolean) -> Unit) {
    if (pairs.isEmpty()) {
        Text(text = "Список пар ще завантажується…", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        pairs.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = pair.symbol, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = pair.enabled, onCheckedChange = { onToggle(pair.symbol, it) })
            }
        }
    }
}

@Composable
private fun ControlsSection(state: SettingsUiState, viewModel: SettingsViewModel, onOpenLogs: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::pause) { Text("Пауза") }
        OutlinedButton(onClick = viewModel::resume) { Text("Продовжити") }
    }
    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::stop) { Text("Стоп") }
        Button(
            onClick = viewModel::closeAll,
            colors = ButtonDefaults.buttonColors(containerColor = LossRed),
        ) { Text("Закрити все") }
    }
    if (state.killSwitchTriggered) {
        Text(
            text = "🛑 Kill-switch активовано",
            color = LossRed,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = viewModel::resetKillSwitch,
            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
            modifier = Modifier.padding(top = 4.dp),
        ) { Text("Скинути kill-switch") }
    }
    TextButton(onClick = onOpenLogs, modifier = Modifier.padding(top = 8.dp)) { Text("Перегляд логів") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    var logText by remember { mutableStateOf("Завантаження…") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        logText = withContext(Dispatchers.IO) { AppLogger.readTail() }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { scope.launch { reload() } }) { Text("Оновити") }
                },
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Text(
            text = logText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(scrollState),
        )
    }
}
