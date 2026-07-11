package com.scalpbot.bingx.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scalpbot.bingx.R
import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.domain.engine.EngineNotifier
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TradeNotifier"

/**
 * Реалізація EngineNotifier — двигун (domain/engine) нічого не знає про Android
 * API нотифікацій, увесь текст (українською, за форматом з ТЗ) живе тут.
 */
class TradeNotifier(private val context: Context) : EngineNotifier {

    private val ids = AtomicInteger(2000)
    private val manager get() = NotificationManagerCompat.from(context)

    override fun onTradeOpened(trade: TradeEntity) {
        val emoji = if (trade.direction == TradeDirection.LONG) "🟢" else "🔴"
        val direction = directionLabel(trade.direction)
        val text = "$emoji ${baseAsset(trade.symbol)} $direction відкрито — " +
            "вхід ${formatPrice(trade.entryPrice)}, SL ${formatPrice(trade.slPrice)}, TP ${formatPrice(trade.tpPrice)}"
        post(NotificationChannels.TRADES, "Угоду відкрито", text)
    }

    override fun onTradeClosed(trade: TradeEntity, closeReason: CloseReason) {
        val pnlUsd = trade.pnlUsd ?: 0.0
        val pnlPercent = trade.pnlPercent ?: 0.0
        val emoji = if (pnlUsd >= 0) "✅" else "🔴"
        val direction = directionLabel(trade.direction)
        val reasonLabel = closeReasonLabel(closeReason)
        val text = "$emoji ${baseAsset(trade.symbol)} $direction закрито$reasonLabel: " +
            "${formatSignedPercent(pnlPercent)} (${formatSignedUsd(pnlUsd)})"
        post(NotificationChannels.TRADES, "Угоду закрито", text)
    }

    override fun onKillSwitchTriggered() {
        post(
            NotificationChannels.SYSTEM,
            "🛑 Kill-switch",
            "Equity впала на 30% і більше від стартового депозиту. Усі позиції закрито, торгівлю зупинено.",
        )
    }

    override fun onDailyLimitReached() {
        post(
            NotificationChannels.SYSTEM,
            "⏸ Денний ліміт збитку",
            "Досягнуто денний ліміт збитку. Торгівля на паузі до наступної доби.",
        )
    }

    override fun onConnectionLost(message: String) {
        post(NotificationChannels.SYSTEM, "⚠️ Обрив з'єднання", "Втрачено зв'язок з BingX: $message")
    }

    override fun onConnectionRestored() {
        post(NotificationChannels.SYSTEM, "✅ Зв'язок відновлено", "З'єднання з BingX відновлено.")
    }

    override fun onPairAutoDisabled(symbol: String, reason: String) {
        post(
            NotificationChannels.SYSTEM,
            "⏸ Пару ${baseAsset(symbol)} вимкнено автоматично",
            "BingX відхилила ордер по $symbol: $reason. Пару вимкнено в Налаштуваннях, бот перейде до інших пар.",
        )
    }

    fun onReportReady() {
        post(NotificationChannels.SYSTEM, "📊 Готовий дводенний звіт", "Новий розбір результатів бота вже доступний у Журналі.")
    }

    private fun post(channel: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(ids.incrementAndGet(), notification) }
            .onFailure { AppLogger.w(TAG, "Не вдалось показати нотифікацію", it) }
    }

    private fun directionLabel(direction: TradeDirection) = if (direction == TradeDirection.LONG) "Лонг" else "Шорт"

    private fun baseAsset(symbol: String) = symbol.substringBefore("-")

    private fun closeReasonLabel(reason: CloseReason): String = when (reason) {
        CloseReason.TAKE_PROFIT, CloseReason.STOP_LOSS -> ""
        CloseReason.TIMEOUT -> " (тайм-аут)"
        CloseReason.KILL_SWITCH -> " (kill-switch)"
        CloseReason.MANUAL -> " (вручну)"
    }

    private fun formatPrice(price: Double): String {
        val symbols = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' }
        val pattern = if (price >= 1.0) "#,##0.##" else "0.######"
        return DecimalFormat(pattern, symbols).format(price)
    }

    private fun formatSignedPercent(value: Double): String {
        val sign = if (value >= 0) "+" else ""
        return "$sign${"%.2f".format(Locale.US, value)}%"
    }

    private fun formatSignedUsd(value: Double): String {
        val sign = if (value >= 0) "+" else "-"
        return "$sign$${"%.2f".format(Locale.US, kotlin.math.abs(value))}"
    }
}
