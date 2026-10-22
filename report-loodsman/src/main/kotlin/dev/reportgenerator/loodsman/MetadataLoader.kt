package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

object MetadataLoader {

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Сначала читает версию кэша метаданных, затем параллельно загружает типы объектов, связи и атрибуты.
     * Ошибка любого запроса отменяет остальные и пробрасывается.
     * Версия читается до данных, чтобы изменение метаданных во время загрузки привело к повторному обновлению при следующей проверке.
     */
    suspend fun load(api: LoodsmanApi): MetadataSnapshot {
        val startedAt = System.nanoTime()
        val version = timed("version") { api.metaData.cacheVersion() }
        return coroutineScope {
            val types = async { timed("types") { api.metaData.typeList() } }
            val links = async { timed("links") { api.metaData.linkList() } }
            val attributes = async { timed("attributes") { api.metaData.attributeList(mode = 0) } }
            val snapshot = MetadataSnapshot(
                linkTypes = links.await()
                    // Поле `type` в swagger описано противоречиво (описания `type` и `inverseName` перепутаны).
                    // Подтверждено на сервере (2026-10-22): type=0 у 82 из 88 связей, включая «Состоит из ...» и «Документы»;
                    // type=1 — прочие виды связей.
                    .filter { it.type == 0 }
                    .map { LinkTypeInfo(id = it.id, name = it.name) },
                objectTypes = types.await().map {
                    ObjectTypeInfo(
                        id = it.id,
                        typeName = it.typeName,
                        keyAttribute = it.attrName?.trim()?.takeIf(String::isNotEmpty),
                    )
                },
                // Атрибуты сравниваются строго: регистр и «ё» сохраняются, со стороны Loodsman убираем только пробелы по краям.
                attributeNames = attributes.await()
                    .mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                    .toSet(),
                version = version,
            )
            log.info(
                "Metadata loaded in {} ms: version {}, types {}, links {}, attributes {}",
                elapsedMs(startedAt),
                snapshot.version,
                snapshot.objectTypes.size,
                snapshot.linkTypes.size,
                snapshot.attributeNames.size,
            )
            snapshot
        }
    }

    private suspend fun <T> timed(name: String, request: suspend () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return request()
        } finally {
            log.info("Metadata request {} finished in {} ms", name, elapsedMs(startedAt))
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
}
