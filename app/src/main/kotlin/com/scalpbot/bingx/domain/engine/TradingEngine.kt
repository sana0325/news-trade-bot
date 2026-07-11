package com.scalpbot.bingx.domain.engine

import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.db.dao.LessonDao
import com.scalpbot.bingx.data.local.db.dao.PairDao
import com.scalpbot.bingx.data.local.db.dao.TradeDao
import com.scalpbot.bingx.data.local.db.entity.CloseReason
import com.scalpbot.bingx.data.local.db.entity.TradeDirection
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.local.db.entity.TradeStatus
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import com.scalpbot.bingx.data.remote.NetworkClientFactory
import com.scalpbot.bingx.data.remote.bingx.BingXRestClient
import com.scalpbot.bingx.data.remote.bingx.BingXWebSocketClient
import com.scalpbot.bingx.data.remote.bingx.dto.KlineDto
import com.scalpbot.bingx.data.remote.bingx.dto.MarketWsEvent
import com.scalpbot.bingx.data.remote.bingx.dto.NewOrderRequest
import com.scalpbot.bingx.data.remote.bingx.dto.OrderSide
import com.scalpbot.bingx.data.remote.bingx.dto.PositionSide
import com.scalpbot.bingx.data.remote.bingx.dto.TpSlSpec
import com.scalpbot.bingx.data.remote.deepseek.DeepSeekClient
import com.scalpbot.bingx.data.remote.deepseek.PromptBuilder
import com.scalpbot.bingx.domain.model.Candle
import com.scalpbot.bingx.domain.model.EngineState
import com.scalpbot.bingx.domain.model.EngineStatus
import com.scalpbot.bingx.domain.model.MarketContext
import com.scalpbot.bingx.domain.model.TradeAction
import com.scalpbot.bingx.domain.model.TradeHistorySummary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "TradingEngine"
private const val QUICK_MOVE_TRIGGER_PERCENT = 0.5
private const val QUICK_MOVE_MIN_INTERVAL_MS = 20_000L
private const val POSITION_POLL_INTERVAL_MS = 15_000L
private const val EQUITY_POLL_INTERVAL_MS = 20_000L

/**
 * Оркестратор торгової логіки. Не знає нічого про Android Service/нотифікації —
 * запускається/зупиняється ззовні (TradingForegroundService, фаза 6) і повідомляє
 * про події через EngineNotifier. Єдина відкрита позиція — хардкод (SingleTradeLock).
 */
class TradingEngine(
    private val bingXRestClient: BingXRestClient,
    private val bingXWebSocketClient: BingXWebSocketClient,
    private val deepSeekClient: DeepSeekClient,
    private val secureConfigStore: SecureConfigStore,
    private val tradeDao: TradeDao,
    private val pairDao: PairDao,
    private val lessonDao: LessonDao,
    private val riskManager: RiskManager,
    private val singleTradeLock: SingleTradeLock = SingleTradeLock(),
) {
    private val json = NetworkClientFactory.json

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    var notifier: EngineNotifier? = null

    private var engineScope: CoroutineScope? = null
    private var eventsJob: Job? = null
    private var equityJob: Job? = null
    private var monitorJob: Job? = null

    private var isHedgeMode: Boolean = false
    private val lastCandleOpenTimeMs = ConcurrentHashMap<String, Long>()
    private val lastKnownPrice = ConcurrentHashMap<String, Double>()
    private val lastQuickMoveTriggerAt = ConcurrentHashMap<String, Long>()
    private var wasConnected = true

    fun start(scope: CoroutineScope) {
        if (_state.value.status == EngineStatus.RUNNING) return
        engineScope = scope
        secureConfigStore.botActive = true

        scope.launch {
            runCatching { bingXRestClient.syncServerTime() }
            val equity = fetchEquity()
            if (equity != null) riskManager.ensureSessionBaseline(equity)
            isHedgeMode = bingXRestClient.isHedgeMode().getOrDefault(false)

            val killed = secureConfigStore.killSwitchTriggered
            _state.value = _state.value.copy(
                status = if (killed) EngineStatus.KILL_SWITCHED else EngineStatus.RUNNING,
                mode = secureConfigStore.tradingMode,
                equityUsd = equity ?: _state.value.equityUsd,
            )

            val enabledSymbols = pairDao.getEnabledSymbols()
            bingXWebSocketClient.start(scope, enabledSymbols)

            eventsJob = scope.launch { collectMarketEvents() }
            equityJob = scope.launch { pollEquityLoop() }

            tradeDao.getOpenTrade()?.let { openTrade ->
                _state.value = _state.value.copy(
                    openPositionSymbol = openTrade.symbol,
                    openPositionDirection = if (openTrade.direction == TradeDirection.LONG) TradeAction.LONG else TradeAction.SHORT,
                )
                monitorJob = scope.launch { monitorOpenPosition(openTrade.id) }
            }
        }
    }

    fun pause() {
        if (_state.value.status == EngineStatus.RUNNING) {
            _state.value = _state.value.copy(status = EngineStatus.PAUSED)
        }
    }

    fun resume() {
        if (_state.value.status == EngineStatus.PAUSED) {
            _state.value = _state.value.copy(status = EngineStatus.RUNNING)
        }
    }

    fun stop() {
        secureConfigStore.botActive = false
        eventsJob?.cancel(); equityJob?.cancel(); monitorJob?.cancel()
        bingXWebSocketClient.stop()
        _state.value = _state.value.copy(status = EngineStatus.STOPPED)
    }

    suspend fun closeAllNow(reason: CloseReason = CloseReason.MANUAL) {
        bingXRestClient.closeAllPositions()
        val open = tradeDao.getOpenTrade() ?: return
        finalizeTradeClose(open, reason)
    }

    private suspend fun pollEquityLoop() {
        while (engineScope?.isActive == true) {
            val equity = fetchEquity()
            if (equity != null) {
                _state.value = _state.value.copy(equityUsd = equity, lastUpdatedAtEpochMs = System.currentTimeMillis())
                if (riskManager.checkKillSwitch(equity) && _state.value.status != EngineStatus.KILL_SWITCHED) {
                    onKillSwitchTriggered()
                }
            }
            delay(EQUITY_POLL_INTERVAL_MS)
        }
    }

    private suspend fun collectMarketEvents() {
        bingXWebSocketClient.events.collect { event ->
            when (event) {
                is MarketWsEvent.Connected -> {
                    if (!wasConnected) notifier?.onConnectionRestored()
                    wasConnected = true
                }
                is MarketWsEvent.Disconnected -> {
                    wasConnected = false
                    notifier?.onConnectionLost(event.cause?.message ?: "WS розірвано")
                }
                is MarketWsEvent.KlineUpdate -> handleKlineUpdate(event)
                is MarketWsEvent.TickerUpdate -> handleQuickPriceMove(event.symbol, event.lastPrice)
            }
        }
    }

    private fun handleKlineUpdate(event: MarketWsEvent.KlineUpdate) {
        if (event.interval.startsWith("kline_1min") || event.interval == "1min") {
            val key = event.symbol
            val previousOpen = lastCandleOpenTimeMs[key]
            lastCandleOpenTimeMs[key] = event.kline.openTimeMs
            if (previousOpen != null && previousOpen != event.kline.openTimeMs) {
                engineScope?.launch { onM1CandleClosed(event.symbol) }
            }
        }
        handleQuickPriceMove(event.symbol, event.kline.close)
    }

    private fun handleQuickPriceMove(symbol: String, price: Double) {
        if (price <= 0.0) return
        val previous = lastKnownPrice.put(symbol, price) ?: return
        val changePercent = kotlin.math.abs(price - previous) / previous * 100.0
        if (changePercent < QUICK_MOVE_TRIGGER_PERCENT) return
        val now = System.currentTimeMillis()
        val lastTrigger = lastQuickMoveTriggerAt[symbol] ?: 0L
        if (now - lastTrigger < QUICK_MOVE_MIN_INTERVAL_MS) return
        lastQuickMoveTriggerAt[symbol] = now
        engineScope?.launch { onM1CandleClosed(symbol) }
    }

    /** Опитує DeepSeek на закритті M1-свічки (або різкому русі ціни) для однієї пари. */
    private suspend fun onM1CandleClosed(symbol: String) {
        if (_state.value.status != EngineStatus.RUNNING) return
        if (tradeDao.getOpenTrade() != null) return
        if (symbol !in pairDao.getEnabledSymbols()) return

        val context = buildMarketContext(symbol) ?: return
        val raw = deepSeekClient.chat(PromptBuilder.buildSystemPrompt(), PromptBuilder.buildUserPrompt(context))
            .getOrElse {
                AppLogger.w(TAG, "DeepSeek запит не вдався для $symbol", it)
                return
            }

        val decision = when (val validation = DecisionValidator.validate(raw)) {
            is DecisionValidationResult.Valid -> validation.decision
            is DecisionValidationResult.Invalid -> {
                AppLogger.w(TAG, "Невалідна відповідь DeepSeek для $symbol: ${validation.error}")
                return
            }
        }
        if (decision.action == TradeAction.SKIP) return
        val tpPct = decision.tpPct ?: return
        val slPct = decision.slPct ?: return
        if (RiskMath.isSpreadTooWide(context.spreadPercent, tpPct)) {
            AppLogger.i(TAG, "$symbol: спред ${context.spreadPercent}% занадто широкий відносно TP $tpPct%, skip")
            return
        }

        singleTradeLock.withExclusiveAccess {
            if (tradeDao.getOpenTrade() != null) return@withExclusiveAccess
            val equity = fetchEquity() ?: return@withExclusiveAccess
            when (val risk = riskManager.canOpenNewTrade(equity)) {
                is RiskCheckResult.Blocked -> {
                    AppLogger.i(TAG, "Ризик заблокував угоду по $symbol: ${risk.reason}")
                    if (risk.reason.contains("денний ліміт", ignoreCase = true)) notifier?.onDailyLimitReached()
                }
                RiskCheckResult.Allowed -> openTrade(symbol, decision.action, slPct, tpPct, decision.reason, decision.confidence, context, equity)
            }
        }
    }

    private suspend fun openTrade(
        symbol: String,
        direction: TradeAction,
        slPct: Double,
        tpPct: Double,
        reason: String,
        confidence: Double,
        context: MarketContext,
        availableEquityUsd: Double,
    ) {
        val pair = pairDao.getEnabled().firstOrNull { it.symbol == symbol } ?: run {
            AppLogger.w(TAG, "$symbol: немає кешованих даних контракту, skip"); return
        }
        val entryPrice = context.candlesM1.lastOrNull()?.close ?: return

        val quantity = PositionSizer.computeQuantity(
            PositionSizer.Input(
                availableBalanceUsd = availableEquityUsd,
                marginPercent = secureConfigStore.marginPercent,
                leverage = secureConfigStore.leverage,
                entryPrice = entryPrice,
                stepSize = pair.stepSize,
                minQty = pair.minQty,
            ),
        )
        if (quantity <= 0.0) {
            AppLogger.i(TAG, "$symbol: розмір позиції менший за minQty при поточному депозиті, skip")
            return
        }

        val sltp = SlTpCalculator.compute(entryPrice, direction, slPct, tpPct, pair.tickSize)
        val positionSide = if (isHedgeMode) {
            if (direction == TradeAction.LONG) PositionSide.LONG else PositionSide.SHORT
        } else {
            PositionSide.BOTH
        }
        val side = if (direction == TradeAction.LONG) OrderSide.BUY else OrderSide.SELL

        val orderRequest = NewOrderRequest(
            symbol = symbol,
            side = side,
            positionSide = positionSide,
            quantity = quantity,
            stopLoss = TpSlSpec(type = "STOP_MARKET", stopPrice = sltp.slPrice),
            takeProfit = TpSlSpec(type = "TAKE_PROFIT_MARKET", stopPrice = sltp.tpPrice),
        )

        bingXRestClient.placeOrder(orderRequest).onSuccess { orderResult ->
            val marginUsd = PositionSizer.marginUsdFor(availableEquityUsd, secureConfigStore.marginPercent)
            val trade = TradeEntity(
                symbol = symbol,
                direction = if (direction == TradeAction.LONG) TradeDirection.LONG else TradeDirection.SHORT,
                status = TradeStatus.OPEN,
                openedAtEpochMs = System.currentTimeMillis(),
                closedAtEpochMs = null,
                entryPrice = orderResult.price.takeIf { it > 0 } ?: entryPrice,
                slPrice = sltp.slPrice,
                tpPrice = sltp.tpPrice,
                exitPrice = null,
                leverage = secureConfigStore.leverage,
                marginUsd = marginUsd,
                quantity = quantity,
                pnlUsd = null,
                pnlPercent = null,
                aiReason = reason,
                aiConfidence = confidence,
                marketContextJson = runCatching { json.encodeToString(context.candlesM1) }.getOrDefault("[]"),
                closeReason = null,
                lessonsVersion = lessonDao.getActive()?.version,
            )
            val id = tradeDao.insert(trade)
            val saved = trade.copy(id = id)
            _state.value = _state.value.copy(openPositionSymbol = symbol, openPositionDirection = direction)
            notifier?.onTradeOpened(saved)
            monitorJob = engineScope?.launch { monitorOpenPosition(id) }
        }.onFailure {
            AppLogger.e(TAG, "Не вдалось відкрити ордер по $symbol", it)
        }
    }

    private suspend fun monitorOpenPosition(tradeId: Long) {
        while (engineScope?.isActive == true) {
            val trade = tradeDao.getOpenTrade()
            if (trade == null || trade.id != tradeId) return

            val equity = fetchEquity()
            if (equity != null && riskManager.checkKillSwitch(equity)) {
                onKillSwitchTriggered()
                bingXRestClient.closeAllPositions()
                finalizeTradeClose(trade, CloseReason.KILL_SWITCH)
                return
            }

            val elapsedMinutes = (System.currentTimeMillis() - trade.openedAtEpochMs) / 60_000L
            if (elapsedMinutes >= secureConfigStore.maxHoldMinutes) {
                bingXRestClient.closeAllPositions()
                finalizeTradeClose(trade, CloseReason.TIMEOUT)
                return
            }

            val positions = bingXRestClient.getPositions(trade.symbol).getOrNull()
            val stillOpen = positions?.any { kotlin.math.abs(it.positionAmt) > 0.0 } == true
            if (!stillOpen) {
                // Позиція вже закрита на біржі (TP/SL спрацював там, не в додатку). Без
                // ідентифікатора ордера, що її закрив, визначаємо TP/SL за тим, до якої
                // з двох цін останній відомий маркет-прайс ближчий.
                val exitPrice = positions?.firstOrNull()?.markPrice ?: lastKnownPrice[trade.symbol] ?: trade.tpPrice
                val distanceToTp = kotlin.math.abs(exitPrice - trade.tpPrice)
                val distanceToSl = kotlin.math.abs(exitPrice - trade.slPrice)
                val closeReason = if (distanceToTp <= distanceToSl) CloseReason.TAKE_PROFIT else CloseReason.STOP_LOSS
                finalizeTradeClose(trade, closeReason, exitPrice)
                return
            }

            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    private suspend fun finalizeTradeClose(trade: TradeEntity, reason: CloseReason, exitPriceOverride: Double? = null) {
        val exitPrice = exitPriceOverride ?: trade.exitPrice ?: trade.entryPrice
        val direction = if (trade.direction == TradeDirection.LONG) 1.0 else -1.0
        val pnlPercent = ((exitPrice - trade.entryPrice) / trade.entryPrice) * direction * 100.0 * trade.leverage
        val pnlUsd = trade.marginUsd * (pnlPercent / 100.0)

        val closed = trade.copy(
            status = TradeStatus.CLOSED,
            closedAtEpochMs = System.currentTimeMillis(),
            exitPrice = exitPrice,
            pnlUsd = pnlUsd,
            pnlPercent = pnlPercent,
            closeReason = reason,
        )
        tradeDao.update(closed)
        riskManager.onTradeClosed()
        _state.value = _state.value.copy(
            openPositionSymbol = null,
            openPositionDirection = null,
            pnlTodayUsd = _state.value.pnlTodayUsd + pnlUsd,
        )
        notifier?.onTradeClosed(closed, reason)
    }

    private fun onKillSwitchTriggered() {
        _state.value = _state.value.copy(status = EngineStatus.KILL_SWITCHED)
        notifier?.onKillSwitchTriggered()
    }

    private suspend fun fetchEquity(): Double? = bingXRestClient.getBalance().getOrNull()?.equity

    private suspend fun buildMarketContext(symbol: String): MarketContext? {
        val m1 = bingXRestClient.getKlines(symbol, "1min", 30).getOrNull() ?: return null
        val m5 = bingXRestClient.getKlines(symbol, "5min", 20).getOrNull() ?: emptyList()
        val m15 = bingXRestClient.getKlines(symbol, "15min", 10).getOrNull() ?: emptyList()
        val bookTicker = bingXRestClient.getBookTicker(symbol).getOrNull()
        val premium = bingXRestClient.getPremiumIndex(symbol).getOrNull()
        val pair = pairDao.getEnabled().firstOrNull { it.symbol == symbol }
        val recentTrades = tradeDao.getRecentClosed(10).map {
            TradeHistorySummary(
                symbol = it.symbol,
                direction = if (it.direction == TradeDirection.LONG) TradeAction.LONG else TradeAction.SHORT,
                pnlPercent = it.pnlPercent ?: 0.0,
                closeReason = it.closeReason?.name ?: "",
            )
        }
        val activeLessons = lessonDao.getActive()?.let {
            runCatching { Json.decodeFromString<List<String>>(it.contentJson) }.getOrDefault(emptyList())
        } ?: emptyList()

        return MarketContext(
            symbol = symbol,
            candlesM1 = m1.map { it.toCandle() },
            candlesM5 = m5.map { it.toCandle() },
            candlesM15 = m15.map { it.toCandle() },
            spreadPercent = bookTicker?.spreadPercent() ?: 0.0,
            fundingRatePercent = (premium?.lastFundingRate ?: 0.0) * 100.0,
            volume24h = pair?.volume24h ?: 0.0,
            openPosition = null,
            recentTrades = recentTrades,
            activeLessons = activeLessons,
        )
    }

    private fun KlineDto.toCandle() = Candle(openTimeMs, open, high, low, close, volume)
}
