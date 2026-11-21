package com.ndscan.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class Status(
    val state: String = "stopped",   // stopped / starting / running / restarting / stopping
    val started: Long = 0,
    val block: Long? = null,
    val lag: Long? = null,
    val loopTs: Long = 0,
    val aliveTs: Long = 0,
    val watch: Int = 0,
    val deposits: Int = 0,
    val signals6h: Int = 0,
    val trades: Int = 0,
) {
    /** Останній сигнал «живий» від Python, мс. */
    val heartbeat get() = maxOf(loopTs, aliveTs)

    companion object {
        fun fromJson(s: String, prev: Status): Status {
            val o = JSONObject(s)
            fun sec(k: String) = (o.optDouble(k, 0.0) * 1000).toLong()
            fun lng(k: String) = if (o.isNull(k)) null else o.optLong(k)
            return prev.copy(
                state = o.optString("state", prev.state),
                block = lng("block"), lag = lng("lag"),
                loopTs = sec("loop_ts"), aliveTs = sec("alive_ts"),
                watch = o.optInt("watch"), deposits = o.optInt("deposits"),
                signals6h = o.optInt("signals_6h"), trades = o.optInt("trades"),
            )
        }
    }
}

/** Спільний стан сервісу для UI. */
object BotState {
    const val MAX_LOG = 500
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    fun setStatus(s: Status) { _status.value = s }
    fun update(f: (Status) -> Status) = _status.update(f)

    fun addLog(line: String) = _log.update { (it + line).takeLast(MAX_LOG) }
}

/** Python кличе onLog() з потоку бота (див. ndscan.LOG_CB). */
class LogSink {
    fun onLog(line: String) = BotState.addLog(line)
}
