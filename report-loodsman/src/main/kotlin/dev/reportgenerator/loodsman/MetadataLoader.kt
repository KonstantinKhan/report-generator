package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

object MetadataLoader {

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Сначала читает версию кэша метаданных, затем параллельно загружает типы объектов, связи, атрибуты и связи между типами.
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
            val typeLinks = async { timed("typeLinks") { api.metaDataCustom.typesLinks() } }
            val snapshot = MetadataSnapshot(
                linkTypes = links.await()
                    // type=1 — горизонтальные связи («Взаимозаменяемые», «Чек-лист» и др.), дублей имён нет.
                    .map { LinkTypeInfo(id = it.id, name = it.name, horizontal = it.type == 1) },
                objectTypes = types.await().map {
                    ObjectTypeInfo(
                        id = it.id,
                        typeName = it.typeName,
                        keyAttribute = it.attrName?.takeIf(String::isNotEmpty),
                    )
                },
                // Имена сравниваются строго, как есть: регистр, «ё» и пробелы не модифицируются.
                attributeNames = attributes.await()
                    .mapNotNull { it.name?.takeIf(String::isNotEmpty) }
                    .toSet(),
                version = version,
                typeLinks = typeLinks.await().map {
                    TypeLinkInfo(
                        typeId1 = it.typeId1,
                        typeId2 = it.typeId2,
                        idLinkType = it.idLinkType,
                        direction = it.direction,
                    )
                },
            )
            log.info(
                "Metadata loaded in {} ms: version {}, types {}, links {}, attributes {}, type links {}",
                elapsedMs(startedAt),
                snapshot.version,
                snapshot.objectTypes.size,
                snapshot.linkTypes.size,
                snapshot.attributeNames.size,
                snapshot.typeLinks.size,
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
