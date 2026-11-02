package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.GetOriginalLinkAttributesOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.github.konstantinkhan.loodsman.dto.V3_GetInfoAboutVersionMode20OutputDto
import khan366kos.mapping.AttributeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Контекст одной загрузки ([LoodsmanProductStructureClient.loadSession]): общий лимит одновременных запросов
 * и кэш ответов Loodsman на время загрузки. Нигде не живёт дольше сессии: кэш не переходит между вызовами.
 * Незавершённые запросы сессии отменяются при её закрытии.
 */
internal class LoadSession(
    concurrency: Int,
    private val scope: CoroutineScope,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<LoadSession>

    val limiter = Limiter(concurrency)
    val props = Memo<Int, PropObjectOutputDto>(scope)
    val attributes = Memo<AttributesKey, List<ObjectAttributeOutputDto>>(scope)
    val links = Memo<LinksKey, List<GetLinkedObjectsForObjectsOutputDto_V4>>(scope)
    val linkAttributes = Memo<Int, List<GetOriginalLinkAttributesOutputDto>>(scope)
    val replacements = Memo<Int, List<V3_GetInfoAboutVersionMode20OutputDto>>(scope)

    fun close() = scope.cancel()
}

/** Ключ запроса атрибутов объекта (mode-3): объект и набор запрошенных атрибутов. */
internal data class AttributesKey(val id: Int, val request: AttributeRequest)

/** Ключ запроса связей: объект, отсортированные id типов связей и направление. */
internal data class LinksKey(val parentId: Int, val linkTypeIds: List<Int>, val inverse: Boolean)

/**
 * Кэш значений по ключу с дедупликацией запросов в полёте: ключ, который уже грузится, не грузится второй раз,
 * ждущие получают тот же результат (или ту же ошибку, без записи в кэш). Загрузка идёт в [scope] сессии, а не
 * в контексте вызывающего, поэтому отмена одного вызывающего не отменяет загрузку для остальных.
 */
internal class Memo<K : Any, V : Any>(private val scope: CoroutineScope) {

    private val entries = ConcurrentHashMap<K, CompletableDeferred<V>>()

    /**
     * Значения по [keys]. Ключи без записи грузит [loadMissing] одним вызовом (он должен вернуть все запрошенные
     * ключи). Вызывать из кода вне [loadMissing] и вне лимита запросов: ждущий не должен держать разрешение.
     */
    suspend fun getAll(keys: Collection<K>, loadMissing: suspend (List<K>) -> Map<K, V>): Map<K, V> {
        val owned = LinkedHashMap<K, CompletableDeferred<V>>()
        val awaited = LinkedHashMap<K, CompletableDeferred<V>>()
        for (key in keys.distinct()) {
            val fresh = CompletableDeferred<V>()
            val existing = entries.putIfAbsent(key, fresh)
            if (existing == null) owned[key] = fresh else awaited[key] = existing
        }
        if (owned.isNotEmpty()) startLoading(owned, loadMissing)
        return (owned + awaited).mapValues { (_, deferred) -> deferred.await() }
    }

    suspend fun get(key: K, load: suspend () -> V): V = getAll(listOf(key)) { mapOf(key to load()) }.getValue(key)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startLoading(owned: Map<K, CompletableDeferred<V>>, loadMissing: suspend (List<K>) -> Map<K, V>) {
        val loading = scope.async { loadMissing(owned.keys.toList()) }
        loading.invokeOnCompletion { cause ->
            if (cause == null) {
                val loaded = loading.getCompleted()
                owned.forEach { (key, deferred) ->
                    val value = loaded[key]
                    if (value != null) {
                        deferred.complete(value)
                    } else {
                        entries.remove(key, deferred)
                        deferred.completeExceptionally(IllegalStateException("Loader returned no value for $key"))
                    }
                }
            } else {
                owned.forEach { (key, deferred) ->
                    entries.remove(key, deferred)
                    deferred.completeExceptionally(cause)
                }
            }
        }
    }
}
