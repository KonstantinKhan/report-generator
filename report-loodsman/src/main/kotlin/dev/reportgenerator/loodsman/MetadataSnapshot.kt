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
}

data class LinkTypeInfo(val id: Int, val name: String?)

/** [keyAttribute] — ключевой атрибут типа как есть; null, если не задан или пуст. */
data class ObjectTypeInfo(val id: Int, val typeName: String?, val keyAttribute: String?)
