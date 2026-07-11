package com.scalpbot.bingx.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object NetworkClientFactory {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        // Без цього kotlinx.serialization мовчки не серіалізує поля, чиє значення
        // збігається з дефолтом у data-класі (напр. DeepSeekResponseFormat.type,
        // TpSlSpec.workingType) — DeepSeek/BingX отримували запит без обов'язкового
        // поля замість очікуваного значення, обидва мовчки відхиляли запит.
        encodeDefaults = true
    }

    fun create(): HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
        install(Logging) { level = LogLevel.INFO }
    }
}
