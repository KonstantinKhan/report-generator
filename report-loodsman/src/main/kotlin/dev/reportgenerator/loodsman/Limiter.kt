package dev.reportgenerator.loodsman

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Ограничивает число одновременных запросов к Loodsman. Один экземпляр живёт на всю загрузку ([LoadSession]),
 * поэтому лимит общий для всех деревьев вызова. Внутри [block] нельзя вызывать другой лимит или ждать кэш:
 * разрешение держится только на время одного HTTP-запроса.
 */
internal class Limiter(concurrency: Int) {

    private val semaphore = Semaphore(concurrency)

    /** Выполняет [block] для каждого id; результаты по id. */
    suspend fun <T> withPermits(ids: List<Int>, block: suspend (Int) -> T): Map<Int, T> =
        ids.zip(mapPermitted(ids, block)).toMap()

    /** Выполняет [block] под одним разрешением. */
    suspend fun <T> permit(block: suspend () -> T): T = semaphore.withPermit { block() }

    /** Выполняет [block] для каждого элемента под лимитом; результаты в порядке [items]. */
    suspend fun <T, R> mapPermitted(items: List<T>, block: suspend (T) -> R): List<R> = coroutineScope {
        items.map { item -> async { semaphore.withPermit { block(item) } } }.awaitAll()
    }
}
