package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object MetadataLoader {

    /**
     * Сначала читает версию кэша метаданных, затем параллельно загружает типы объектов и связи.
     * Ошибка любого запроса отменяет остальные и пробрасывается.
     * Версия читается до данных, чтобы изменение метаданных во время загрузки привело к повторному обновлению при следующей проверке.
     */
    suspend fun load(api: LoodsmanApi): MetadataSnapshot {
        val version = api.metaData.cacheVersion()
        return coroutineScope {
            val types = async { api.metaData.typeList() }
            val links = async { api.metaData.linkList() }
            MetadataSnapshot(
                linkTypes = links.await()
                    // Поле `type` в swagger описано противоречиво (описания `type` и `inverseName` перепутаны).
                    // Подтверждено на сервере (2026-10-22): type=0 у 82 из 88 связей, включая «Состоит из ...» и «Документы»;
                    // type=1 — прочие виды связей.
                    .filter { it.type == 0 }
                    .map { LinkTypeInfo(id = it.id, name = it.name) },
                objectTypes = types.await().map { ObjectTypeInfo(id = it.id, typeName = it.typeName) },
                version = version,
            )
        }
    }
}
