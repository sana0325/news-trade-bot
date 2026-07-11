package com.scalpbot.bingx.data.remote.bingx

import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.core.util.RetryBackoff
import com.scalpbot.bingx.data.remote.bingx.dto.KlineDto
import com.scalpbot.bingx.data.remote.bingx.dto.MarketWsEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "BingXWebSocketClient"

/**
 * Публічний ринковий WS BingX. ВАЖЛИВО: BingX стискає фрейми через GZIP і
 * очікує текстову відповідь "Pong" на "Ping" — обидва моменти оброблені нижче.
 * Точний формат каналів (dataType/поля kline) — найкраще наближення до
 * документації BingX Swap V2; парсинг навмисно захисний (JsonElement, а не
 * жорсткий data-клас), щоб один незнайомий формат повідомлення не рвав стрім.
 */
class BingXWebSocketClient(private val httpClient: HttpClient) {

    private val _events = MutableSharedFlow<MarketWsEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MarketWsEvent> = _events.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val subscriptionsMutex = Mutex()
    private var desiredSymbols: Set<String> = emptySet()
    private var job: Job? = null
    private var sendChannel: (suspend (String) -> Unit)? = null

    fun start(scope: CoroutineScope, symbols: List<String>) {
        desiredSymbols = symbols.toSet()
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    connectAndListen()
                    attempt = 0
                } catch (t: CancellationException) {
                    throw t // навмисна зупинка (stop()/зміна мережі) — не помилка, не логуємо як обрив
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "WS session ended", t)
                    _events.tryEmit(MarketWsEvent.Disconnected(t))
                }
                if (!isActive) break
                val delayMs = RetryBackoff.nextDelayMs(attempt)
                AppLogger.i(TAG, "Reconnecting market WS in ${delayMs}ms (attempt $attempt)")
                delay(delayMs)
                attempt++
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sendChannel = null
    }

    /** Форсує перепідписку — викликати після зміни набору активних пар або зміни мережі. */
    suspend fun updateSymbols(symbols: List<String>) {
        subscriptionsMutex.withLock { desiredSymbols = symbols.toSet() }
        sendChannel?.let { send -> symbols.forEach { subscribe(send, it) } }
    }

    private suspend fun connectAndListen() {
        httpClient.webSocket(BingXEndpoints.MARKET_WS_URL) {
            sendChannel = { text -> send(Frame.Text(text)) }
            _events.emit(MarketWsEvent.Connected)
            subscriptionsMutex.withLock { desiredSymbols }.forEach { subscribe(sendChannel!!, it) }

            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> handlePayload(gunzip(frame.readBytes()))
                    is Frame.Text -> handlePayload(frame.readText())
                    is Frame.Close -> return@webSocket
                    else -> Unit
                }
            }
        }
    }

    private suspend fun subscribe(send: suspend (String) -> Unit, symbol: String) {
        listOf("kline_1min", "kline_5min").forEach { dataType ->
            val payload = """{"id":"${UUID.randomUUID()}","reqType":"sub","dataType":"$symbol@$dataType"}"""
            send(payload)
        }
        send("""{"id":"${UUID.randomUUID()}","reqType":"sub","dataType":"$symbol@ticker"}""")
    }

    private suspend fun handlePayload(text: String) {
        if (text == "Ping") {
            sendChannel?.invoke("Pong")
            return
        }
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val dataType = root["dataType"]?.jsonPrimitive?.content ?: return
            val symbol = dataType.substringBefore("@")
            val data = root["data"] ?: return
            when {
                dataType.contains("kline") -> parseKline(symbol, dataType.substringAfter("@"), data)
                dataType.contains("ticker") -> parseTicker(symbol, data)
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Не вдалось розпарсити WS-повідомлення, пропускаю", t)
        }
    }

    private suspend fun parseKline(symbol: String, interval: String, data: JsonElement) {
        val obj: JsonObject = when (data) {
            is JsonArray -> data.jsonArray.lastOrNull()?.jsonObject ?: return
            is JsonObject -> data
            else -> return
        }
        val kline = KlineDto(
            openTimeMs = obj["T"]?.jsonPrimitive?.longOrNull ?: obj["time"]?.jsonPrimitive?.longOrNull ?: 0L,
            open = obj["o"]?.jsonPrimitive?.doubleOrNull ?: obj["open"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            high = obj["h"]?.jsonPrimitive?.doubleOrNull ?: obj["high"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            low = obj["l"]?.jsonPrimitive?.doubleOrNull ?: obj["low"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            close = obj["c"]?.jsonPrimitive?.doubleOrNull ?: obj["close"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            volume = obj["v"]?.jsonPrimitive?.doubleOrNull ?: obj["volume"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
        _events.emit(MarketWsEvent.KlineUpdate(symbol, interval, kline))
    }

    private suspend fun parseTicker(symbol: String, data: JsonElement) {
        val obj: JsonObject = when (data) {
            is JsonArray -> data.jsonArray.lastOrNull()?.jsonObject ?: return
            is JsonObject -> data
            else -> return
        }
        val lastPrice = obj["c"]?.jsonPrimitive?.doubleOrNull ?: obj["lastPrice"]?.jsonPrimitive?.doubleOrNull ?: return
        val changePercent = obj["r"]?.jsonPrimitive?.doubleOrNull ?: obj["priceChangePercent"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        _events.emit(MarketWsEvent.TickerUpdate(symbol, lastPrice, changePercent))
    }

    private fun gunzip(bytes: ByteArray): String {
        GZIPInputStream(bytes.inputStream()).use { gz ->
            val out = ByteArrayOutputStream()
            gz.copyTo(out)
            return out.toString("UTF-8")
        }
    }
}
