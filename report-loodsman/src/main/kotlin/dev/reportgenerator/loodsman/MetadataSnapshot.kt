package dev.reportgenerator.loodsman

/**
 * Immutable снимок метаданных Loodsman, нужный для разрешения типов связей и объектов.
 */
data class MetadataSnapshot(
    val linkTypes: List<LinkTypeInfo>,
    val objectTypes: List<ObjectTypeInfo>,
    /** Имена атрибутов Loodsman как есть; пустые и отсутствующие имена отброшены. */
    val attributeNames: Set<String>,
    val version: Long,
    /** Связи между типами объектов (metaDataCustom types-links); пусто, если не загружены. */
    val typeLinks: List<TypeLinkInfo> = emptyList(),
) {

    fun linkTypeId(pdmName: String): Int? =
        linkTypes.firstOrNull { it.name == pdmName }?.id

    fun hasObjectType(typeName: String): Boolean =
        objectTypes.any { it.typeName == typeName }

    /**
     * Ключевой атрибут типа объекта (`attrName` из get-type-list) или null, если тип неизвестен или ключ не задан.
     * Значение ключа физически лежит в свойстве `product`, а не в списке атрибутов объекта.
     */
    fun keyAttributeOf(typeName: String): String? =
        objectTypes.firstOrNull { it.typeName == typeName }?.keyAttribute

    /**
     * Допустимые направления ([TypeLinkInfo.direction]) связи [linkTypeId] для типа объекта с именем [typeName]:
     * 1 — только прямое, -1 — только обратное, 0 — оба. Пусто, если строк для типа нет.
     */
    fun linkDirections(typeName: String, linkTypeId: Int): Set<Int> {
        val typeIds = objectTypes.filter { it.typeName == typeName }.mapTo(HashSet()) { it.id }
        return typeLinks
            .filter { it.typeId1 in typeIds && it.idLinkType == linkTypeId }
            .mapTo(HashSet()) { it.direction }
    }
}

/** [horizontal] — связь горизонтальная (type=1 в get-link-list), например «Взаимозаменяемые». */
data class LinkTypeInfo(val id: Int, val name: String?, val horizontal: Boolean = false)

/** Строка metaDataCustom types-links: тип [typeId1] может быть источником связи [idLinkType] к типу [typeId2] в [direction]. */
data class TypeLinkInfo(val typeId1: Int, val typeId2: Int, val idLinkType: Int, val direction: Int)

/** [keyAttribute] — ключевой атрибут типа как есть; null, если не задан или пуст. */
data class ObjectTypeInfo(val id: Int, val typeName: String?, val keyAttribute: String?)
