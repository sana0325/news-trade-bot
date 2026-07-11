package com.scalpbot.bingx.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.ui.graphics.vector.ImageVector

enum class ScalpDestination(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Дашборд", Icons.Filled.Home),
    Market("market", "Ринок", Icons.Filled.ShowChart),
    Journal("journal", "Журнал", Icons.Filled.Receipt),
    Statistics("statistics", "Статистика", Icons.Filled.BarChart),
    Settings("settings", "Налаштування", Icons.Filled.Settings),
}

object ScalpRoutes {
    const val MARKET_DETAIL = "market/{symbol}"
    fun marketDetail(symbol: String) = "market/$symbol"

    const val LOG_VIEWER = "settings/logs"
}
