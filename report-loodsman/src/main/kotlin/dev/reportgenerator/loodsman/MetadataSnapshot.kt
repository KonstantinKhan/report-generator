package dev.reportgenerator.loodsman

/**
 * Immutable снимок метаданных Loodsman, нужный для разрешения типов связей и объектов.
 */
data class MetadataSnapshot(
    val linkTypes: List<LinkTypeInfo>,
    val objectTypes: List<ObjectTypeInfo>,
    /** Имена атрибутов Loodsman без пробелов по краям; пустые и отсутствующие имена отброшены. */
    val attributeNames: Set<String>,
    val version: Long,
) {

    fun linkTypeId(pdmName: String): Int? {
        val target = normalizeForComparison(pdmName)
        return linkTypes.firstOrNull { normalizeForComparison(it.name.orEmpty()) == target }?.id
    }

    fun hasObjectType(typeName: String): Boolean {
        val target = normalizeForComparison(typeName)
        return objectTypes.any { info -> info.typeName?.let(::normalizeForComparison) == target }
    }

    /**
     * Ключевой атрибут типа объекта (`attrName` из get-type-list) или null, если тип неизвестен или ключ не задан.
     * Значение ключа физически лежит в свойстве `product`, а не в списке атрибутов объекта.
     */
    fun keyAttributeOf(typeName: String): String? {
        val target = normalizeForComparison(typeName)
        return objectTypes.firstOrNull { info -> info.typeName?.let(::normalizeForComparison) == target }?.keyAttribute
    }
}

data class LinkTypeInfo(val id: Int, val name: String?)

/** [keyAttribute] — ключевой атрибут типа, обрезан по краям; null, если не задан. */
data class ObjectTypeInfo(val id: Int, val typeName: String?, val keyAttribute: String?)
