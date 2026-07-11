package com.scalpbot.bingx.ui.settings

import androidx.compose.runtime.Composable
import com.scalpbot.bingx.ui.common.ComingSoonScreen

@Composable
fun SettingsScreen(onOpenLogs: () -> Unit) {
    ComingSoonScreen(title = "Налаштування")
}

@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    ComingSoonScreen(title = "Логи")
}
