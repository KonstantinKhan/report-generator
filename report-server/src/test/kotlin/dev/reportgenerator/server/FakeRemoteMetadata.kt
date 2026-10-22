package dev.reportgenerator.server

import dev.reportgenerator.loodsman.MetadataCache
import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.ObjectTypeInfo
import java.util.concurrent.atomic.AtomicInteger

// Виды из loodsman-mapping.yaml: у каждого типа ключевой атрибут «Обозначение».
private val KIND_TYPE_NAMES = listOf(
    "Деталь", "Сборочная единица", "Сборочный чертеж", "Комплекс",
    "Стандартное изделие", "Прочее изделие", "Материал по КД", "Комплект",
)

// Stands in for Loodsman metadata: the cache version and load errors are set by the test.
class FakeRemoteMetadata {
    var version = 1L
    var versionError: Throwable? = null
    var loadError: Throwable? = null
    val loads = AtomicInteger(0)

    fun cache() = MetadataCache(
        versionOf = {
            versionError?.let { throw it }
            version
        },
        loadSnapshot = {
            loads.incrementAndGet()
            loadError?.let { throw it }
            MetadataSnapshot(
                linkTypes = emptyList(),
                objectTypes = KIND_TYPE_NAMES.mapIndexed { index, name ->
                    ObjectTypeInfo(id = index + 1, typeName = name, keyAttribute = "Обозначение")
                },
                attributeNames = setOf("Обозначение", "Наименование"),
                version = version,
            )
        },
        validate = { emptyList() },
    )
}
