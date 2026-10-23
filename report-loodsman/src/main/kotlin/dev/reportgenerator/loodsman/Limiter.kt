package dev.reportgenerator.loodsman

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Ограничивает число одновременных запросов к Loodsman при параллельной загрузке. */
internal class Limiter(private val concurrency: Int) {

    /** Выполняет [block] для каждого id; семафор создаётся на каждый вызов. Результаты по id. */
    suspend fun <T> withPermits(ids: List<Int>, block: suspend (Int) -> T): Map<Int, T> =
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            ids.map { id -> async { id to semaphore.withPermit { block(id) } } }.awaitAll().toMap()
        }
}
