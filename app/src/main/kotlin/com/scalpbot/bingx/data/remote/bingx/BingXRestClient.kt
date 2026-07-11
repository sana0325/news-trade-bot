package com.scalpbot.bingx.data.remote.bingx

import com.scalpbot.bingx.core.util.AppLogger
import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import com.scalpbot.bingx.data.remote.NetworkClientFactory
import com.scalpbot.bingx.data.remote.bingx.dto.BalanceDto
import com.scalpbot.bingx.data.remote.bingx.dto.BalanceEnvelopeData
import com.scalpbot.bingx.data.remote.bingx.dto.BingXEnvelope
import com.scalpbot.bingx.data.remote.bingx.dto.BookTickerDto
import com.scalpbot.bingx.data.remote.bingx.dto.CloseAllPositionsData
import com.scalpbot.bingx.data.remote.bingx.dto.ContractDto
import com.scalpbot.bingx.data.remote.bingx.dto.KlineDto
import com.scalpbot.bingx.data.remote.bingx.dto.ListenKeyData
import com.scalpbot.bingx.data.remote.bingx.dto.NewOrderRequest
import com.scalpbot.bingx.data.remote.bingx.dto.OrderResponseData
import com.scalpbot.bingx.data.remote.bingx.dto.OrderResultDto
import com.scalpbot.bingx.data.remote.bingx.dto.PositionDto
import com.scalpbot.bingx.data.remote.bingx.dto.PositionModeDto
import com.scalpbot.bingx.data.remote.bingx.dto.PremiumIndexDto
import com.scalpbot.bingx.data.remote.bingx.dto.ServerTimeData
import com.scalpbot.bingx.data.remote.bingx.dto.Ticker24hDto
import com.scalpbot.bingx.data.remote.bingx.dto.TpSlSpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import kotlinx.serialization.json.Json

private const val TAG = "BingXRestClient"

/**
 * REST-клієнт BingX Perpetual Swap V2. DEMO (VST) і LIVE мають РІЗНІ базові
 * хости (див. BingXEndpoints) — режим/ключі читаються з SecureConfigStore
 * на кожен виклик, тож перемикання DEMO/LIVE у Налаштуваннях підхоплюється одразу.
 */
class BingXRestClient(
    private val httpClient: HttpClient = NetworkClientFactory.create(),
    private val secureConfigStore: SecureConfigStore,
) {
    private val json: Json = NetworkClientFactory.json

    @Volatile private var serverTimeOffsetMs: Long = 0L
    @Volatile private var lastTimeSyncAtMs: Long = 0L

    private fun mode() = secureConfigStore.tradingMode
    private fun baseUrl() = BingXEndpoints.restBaseUrl(mode())
    private fun credentials() = secureConfigStore.apiKeyFor(mode())

    private fun timestamp(): Long = System.currentTimeMillis() + serverTimeOffsetMs

    /** Синхронізує локальний час з сервером BingX; викликати при старті двигуна і раз на ~30 хв. */
    suspend fun syncServerTime(): Result<Long> = runCatching {
        val response = httpClient.get("${baseUrl()}/openApi/swap/v2/server/time")
        val envelope: BingXEnvelope<ServerTimeData> = response.body()
        val serverTime = envelope.data?.serverTime ?: error("BingX: no serverTime in response")
        serverTimeOffsetMs = serverTime - System.currentTimeMillis()
        lastTimeSyncAtMs = System.currentTimeMillis()
        AppLogger.i(TAG, "Server time offset = ${serverTimeOffsetMs}ms")
        serverTimeOffsetMs
    }

    suspend fun ensureTimeSynced() {
        if (System.currentTimeMillis() - lastTimeSyncAtMs > 30 * 60_000L) {
            syncServerTime().onFailure { AppLogger.w(TAG, "Time sync failed", it) }
        }
    }

    // ---- Публічні (без підпису) ----

    suspend fun getContracts(): Result<List<ContractDto>> = publicGet("/openApi/swap/v2/quote/contracts")

    suspend fun getKlines(symbol: String, interval: String, limit: Int = 100): Result<List<KlineDto>> =
        publicGet<List<KlineDto>>(
            "/openApi/swap/v2/quote/klines",
            mapOf("symbol" to symbol, "interval" to interval, "limit" to limit.toString()),
        )

    suspend fun getAllTickers24h(): Result<List<Ticker24hDto>> = publicGet("/openApi/swap/v2/quote/ticker")

    suspend fun getTicker24h(symbol: String): Result<Ticker24hDto> =
        publicGet<Ticker24hDto>("/openApi/swap/v2/quote/ticker", mapOf("symbol" to symbol))

    suspend fun getPremiumIndex(symbol: String): Result<PremiumIndexDto> =
        publicGet<PremiumIndexDto>("/openApi/swap/v2/quote/premiumIndex", mapOf("symbol" to symbol))

    suspend fun getBookTicker(symbol: String): Result<BookTickerDto> =
        publicGet<BookTickerDto>("/openApi/swap/v2/quote/bookTicker", mapOf("symbol" to symbol))

    // ---- Підписані (акаунт) ----

    suspend fun getBalance(): Result<BalanceDto> =
        signedGet<BalanceEnvelopeData>("/openApi/swap/v2/user/balance").map { it.balance }

    suspend fun getPositions(symbol: String? = null): Result<List<PositionDto>> =
        signedGet("/openApi/swap/v2/user/positions", symbol?.let { mapOf("symbol" to it) } ?: emptyMap())

    suspend fun isHedgeMode(): Result<Boolean> =
        signedGet<PositionModeDto>("/openApi/swap/v1/positionSide/dual").map { it.dualSidePosition }

    suspend fun placeOrder(request: NewOrderRequest): Result<OrderResultDto> {
        val params = mutableMapOf(
            "symbol" to request.symbol,
            "side" to request.side.name,
            "positionSide" to request.positionSide.name,
            "type" to "MARKET",
            "quantity" to request.quantity.toString(),
            "stopLoss" to json.encodeToString(TpSlSpec.serializer(), request.stopLoss),
            "takeProfit" to json.encodeToString(TpSlSpec.serializer(), request.takeProfit),
        )
        return signedPost<OrderResponseData>("/openApi/swap/v2/trade/order", params).map { it.order }
    }

    suspend fun closeAllPositions(): Result<CloseAllPositionsData> =
        signedPost("/openApi/swap/v2/trade/closeAllPositions")

    suspend fun createListenKey(): Result<String> =
        signedPost<ListenKeyData>("/openApi/user/auth/userDataStream").map { it.listenKey }

    suspend fun keepAliveListenKey(listenKey: String): Result<Unit> =
        signedPut("/openApi/user/auth/userDataStream", mapOf("listenKey" to listenKey))

    // ---- HTTP helpers ----

    private suspend inline fun <reified T> publicGet(path: String, params: Map<String, String> = emptyMap()): Result<T> =
        runCatching {
            val query = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            val url = if (query.isEmpty()) "${baseUrl()}$path" else "${baseUrl()}$path?$query"
            val response = httpClient.get(url)
            unwrap(response)
        }

    private suspend inline fun <reified T> signedGet(path: String, params: Map<String, String> = emptyMap()): Result<T> =
        runCatching {
            ensureTimeSynced()
            val (apiKey, apiSecret) = credentials()
            require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "BingX API ключі не задані для режиму ${mode()}" }
            val query = BingXSigner.buildSignedQuery(encodedParams(params), apiSecret)
            val response = httpClient.get("${baseUrl()}$path?$query") { header("X-BX-APIKEY", apiKey) }
            unwrap(response)
        }

    private suspend inline fun <reified T> signedPost(path: String, params: Map<String, String> = emptyMap()): Result<T> =
        runCatching {
            ensureTimeSynced()
            val (apiKey, apiSecret) = credentials()
            require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "BingX API ключі не задані для режиму ${mode()}" }
            val query = BingXSigner.buildSignedQuery(encodedParams(params), apiSecret)
            val response = httpClient.post("${baseUrl()}$path?$query") { header("X-BX-APIKEY", apiKey) }
            unwrap(response)
        }

    private suspend fun signedPut(path: String, params: Map<String, String>): Result<Unit> = runCatching {
        ensureTimeSynced()
        val (apiKey, apiSecret) = credentials()
        val query = BingXSigner.buildSignedQuery(encodedParams(params), apiSecret)
        val response = httpClient.put("${baseUrl()}$path?$query") { header("X-BX-APIKEY", apiKey) }
        if (response.status == HttpStatusCode.TooManyRequests) throw BingXRateLimitException()
    }

    private fun encodedParams(params: Map<String, String>): Map<String, String> {
        val withTimestamp = params + mapOf("timestamp" to timestamp().toString())
        return withTimestamp.mapValues { (_, v) -> encode(v) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend inline fun <reified T> unwrap(response: HttpResponse): T {
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw BingXRateLimitException()
        }
        val envelope: BingXEnvelope<T> = response.body()
        if (envelope.code != 0) {
            throw BingXApiException(envelope.code, envelope.msg ?: "Невідома помилка BingX")
        }
        return envelope.data ?: error("BingX: порожній data у відповіді (code=0)")
    }
}

class BingXApiException(val code: Int, message: String) : Exception("BingX error $code: $message")
class BingXRateLimitException : Exception("BingX rate limit (429)")
