package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object MetadataLoader {

    /**
     * Загружает типы объектов и связей параллельно. Ошибка любого запроса отменяет второй и пробрасывается.
     */
    suspend fun load(api: LoodsmanApi): MetadataSnapshot = coroutineScope {
        val types = async { api.metaData.typeList() }
        val links = async { api.metaData.linkList() }
        MetadataSnapshot(
            linkTypes = links.await()
                // Поле `type` в swagger описано противоречиво (описания `type` и `inverseName` перепутаны).
                // Значение 0 подтверждено только фикстурой и текущей работой кода; проверка на живом сервере не выполнена.
                .filter { it.type == 0 }
                .map { LinkTypeInfo(id = it.id, name = it.name) },
            objectTypes = types.await().map { ObjectTypeInfo(id = it.id, typeName = it.typeName) }
        )
    }
}
