package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Кэш снимка метаданных Loodsman. Снимок подменяется целиком и только после успешной загрузки и проверки.
 * Ошибка обновления оставляет предыдущий снимок.
 */
class MetadataCache(
    private val versionOf: suspend () -> Long,
    private val loadSnapshot: suspend () -> MetadataSnapshot,
    private val validate: (MetadataSnapshot) -> List<String>,
) : MetadataSource {

    constructor(
        api: LoodsmanApi,
        validate: (MetadataSnapshot) -> List<String> = { emptyList() },
    ) : this(
        versionOf = { api.metaData.cacheVersion() },
        loadSnapshot = { MetadataLoader.load(api) },
        validate = validate,
    )

    private val log = LoggerFactory.getLogger(this::class.java)

    private val mutex = Mutex()
    private val current = AtomicReference<MetadataSnapshot?>(null)

    /** Первая загрузка. Ошибка пробрасывается: приложение не должно стартовать без метаданных. */
    suspend fun initialize() = mutex.withLock {
        val snapshot = loadSnapshot()
        val errors = validate(snapshot)
        check(errors.isEmpty()) { validationMessage(errors) }
        current.set(snapshot)
        log.info("Metadata cache initialized, version {}", snapshot.version)
    }

    override suspend fun snapshot(): MetadataSnapshot =
        checkNotNull(current.get()) { "Metadata cache is not initialized" }

    /**
     * Сначала сверяет версию кэша. Загрузка идёт, если версия изменилась или передан [force].
     * Вызовы сериализованы: параллельные обновления не дублируют загрузку.
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = mutex.withLock {
        val old = current.get()
            ?: return@withLock RefreshResult.Failed(IllegalStateException("Metadata cache is not initialized"))
        try {
            val remoteVersion = versionOf()
            if (!force && remoteVersion == old.version) return@withLock RefreshResult.Unchanged(old.version)

            val next = loadSnapshot()
            val errors = validate(next)
            check(errors.isEmpty()) { validationMessage(errors) }
            current.set(next)

            if (next.version == old.version) {
                RefreshResult.Unchanged(next.version)
            } else {
                log.info("Metadata cache updated, version {} -> {}", old.version, next.version)
                RefreshResult.Updated(old.version, next.version)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Metadata refresh failed, keeping version {}", old.version, e)
            RefreshResult.Failed(e)
        }
    }

    private fun validationMessage(errors: List<String>) =
        "Metadata validation failed: ${errors.joinToString("; ")}"
}
