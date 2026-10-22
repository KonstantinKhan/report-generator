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
}

data class LinkTypeInfo(val id: Int, val name: String?)

data class ObjectTypeInfo(val id: Int, val typeName: String?)
