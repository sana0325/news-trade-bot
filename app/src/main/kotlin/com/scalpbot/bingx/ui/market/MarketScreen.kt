package com.scalpbot.bingx.ui.market

import androidx.compose.runtime.Composable
import com.scalpbot.bingx.ui.common.ComingSoonScreen

@Composable
fun MarketScreen(onPairClick: (String) -> Unit) {
    ComingSoonScreen(title = "Ринок")
}

@Composable
fun MarketDetailScreen(symbol: String, onBack: () -> Unit) {
    ComingSoonScreen(title = "Графік $symbol")
}
