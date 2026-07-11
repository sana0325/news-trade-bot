package com.scalpbot.bingx.data.remote.deepseek

import com.scalpbot.bingx.data.local.prefs.SecureConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"

/**
 * Токени DeepSeek дешеві — не економимо: опитуємо на закритті кожної M1-свічки
 * по всіх активних парах плюс позачергово при різкому русі ціни (див. TradingEngine).
 */
class DeepSeekClient(
    private val httpClient: HttpClient,
    private val secureConfigStore: SecureConfigStore,
) {

    suspend fun chat(systemPrompt: String, userPrompt: String, temperature: Double = 0.4): Result<String> = runCatching {
        val apiKey = secureConfigStore.deepseekApiKey
        require(apiKey.isNotBlank()) { "DeepSeek API ключ не задано в Налаштуваннях" }

        val request = DeepSeekChatRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userPrompt),
            ),
            temperature = temperature,
            responseFormat = DeepSeekResponseFormat(type = "json_object"),
        )

        val response: HttpResponse = httpClient.post(DEEPSEEK_URL) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw DeepSeekRateLimitException()
        if (response.status.value !in 200..299) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            throw DeepSeekApiException(response.status.value, bodyText)
        }

        val parsed: DeepSeekChatResponse = response.body()
        parsed.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            ?: error("DeepSeek: порожня відповідь")
    }
}

class DeepSeekApiException(val httpCode: Int, val body: String) : Exception("DeepSeek HTTP $httpCode: $body")
class DeepSeekRateLimitException : Exception("DeepSeek rate limit (429)")
