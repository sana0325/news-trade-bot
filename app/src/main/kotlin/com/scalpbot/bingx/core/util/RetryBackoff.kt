package com.scalpbot.bingx.core.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Експоненційний backoff з джиттером — для реконекту WS і повторів REST-викликів
 * при rate-limit/мережевих збоях.
 */
object RetryBackoff {

    suspend fun <T> retry(
        maxAttempts: Int = 5,
        initialDelayMs: Long = 1_000,
        maxDelayMs: Long = 30_000,
        factor: Double = 2.0,
        shouldRetry: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 0
        var delayMs = initialDelayMs
        while (true) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                attempt++
                if (attempt >= maxAttempts || !shouldRetry(t)) throw t
                val jitter = Random.nextLong(0, delayMs / 2 + 1)
                delay(delayMs + jitter)
                delayMs = min((delayMs * factor).toLong(), maxDelayMs)
            }
        }
    }

    /** Затримка для нескінченного реконекту (WS) — не кидає виняток, просто повертає наступний delay. */
    fun nextDelayMs(attempt: Int, initialDelayMs: Long = 1_000, maxDelayMs: Long = 30_000, factor: Double = 2.0): Long {
        val raw = initialDelayMs * factor.pow(attempt)
        return min(raw.toLong().coerceAtLeast(initialDelayMs), maxDelayMs)
    }
}
