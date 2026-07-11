package com.scalpbot.bingx.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scalpbot.bingx.ShiScalpBotApp
import com.scalpbot.bingx.data.local.db.entity.LessonEntity
import com.scalpbot.bingx.data.local.db.entity.ReportEntity
import com.scalpbot.bingx.data.local.db.entity.TradeEntity
import com.scalpbot.bingx.data.local.db.entity.TradeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ResultFilter { ALL, PROFIT, LOSS }

data class JournalFilterState(
    val symbol: String? = null,
    val result: ResultFilter = ResultFilter.ALL,
)

class JournalViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = (application as ShiScalpBotApp).serviceLocator
    private val tradeDao = locator.database.tradeDao()
    private val reportDao = locator.database.reportDao()
    private val lessonDao = locator.database.lessonDao()

    private val _filter = MutableStateFlow(JournalFilterState())
    val filter: StateFlow<JournalFilterState> = _filter

    private val allTrades: StateFlow<List<TradeEntity>> =
        tradeDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableSymbols: StateFlow<List<String>> = allTrades
        .map { trades -> trades.map { it.symbol }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredTrades: StateFlow<List<TradeEntity>> = combine(allTrades, _filter) { trades, filter ->
        trades.filter { trade ->
            (filter.symbol == null || trade.symbol == filter.symbol) &&
                when (filter.result) {
                    ResultFilter.ALL -> true
                    ResultFilter.PROFIT -> trade.status == TradeStatus.CLOSED && (trade.pnlUsd ?: 0.0) >= 0
                    ResultFilter.LOSS -> trade.status == TradeStatus.CLOSED && (trade.pnlUsd ?: 0.0) < 0
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reports: StateFlow<List<ReportEntity>> =
        reportDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lessons: StateFlow<List<LessonEntity>> =
        lessonDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSymbolFilter(symbol: String?) {
        _filter.value = _filter.value.copy(symbol = symbol)
    }

    fun setResultFilter(result: ResultFilter) {
        _filter.value = _filter.value.copy(result = result)
    }

    fun activateLessonVersion(version: Int) {
        viewModelScope.launch { lessonDao.setActiveVersion(version) }
    }
}
