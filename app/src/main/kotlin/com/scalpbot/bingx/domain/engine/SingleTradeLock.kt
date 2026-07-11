package com.scalpbot.bingx.domain.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Хардкод "лише одна позиція одночасно": WS-події по кількох парах можуть
 * прийти майже одночасно, тож "перевір немає відкритої угоди -> відкрий" мусить
 * бути критичною секцією, інакше дві пари можуть відкритись одна за одною.
 */
class SingleTradeLock {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock { block() }
}
