package dev.reportgenerator.loodsman

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Значение, которое загружается suspend-функцией один раз при первом обращении.
 * Параллельные вызовы ждут первую загрузку. Ошибка загрузки не запоминается: следующий вызов повторит попытку.
 * Не реентерабельно: [loader] не должен вызывать [get] того же экземпляра.
 */
internal class SuspendLazy<T : Any>(private val loader: suspend () -> T) {

    private val mutex = Mutex()
    private var value: T? = null

    suspend fun get(): T = mutex.withLock { value ?: loader().also { value = it } }
}
