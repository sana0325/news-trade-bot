package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.data.local.db.entity.TradeEntity

/**
 * Двигун не знає нічого про Android-нотифікації — реалізація (фаза 7) живе в
 * notification/TradeNotifier і підключається сервісом при старті двигуна.
 */
interface EngineNotifier {
    fun onTradeOpened(trade: TradeEntity)
    fun onTradeClosed(trade: TradeEntity, closeReason: CloseReason)
    fun onKillSwitchTriggered()
    fun onDailyLimitReached()
    fun onConnectionLost(message: String)
    fun onConnectionRestored()
}
