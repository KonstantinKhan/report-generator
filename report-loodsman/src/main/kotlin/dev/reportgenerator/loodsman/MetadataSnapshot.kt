package dev.reportgenerator.loodsman

/**
 * Immutable снимок метаданных Loodsman, нужный для разрешения типов связей и объектов.
 */
data class MetadataSnapshot(
    val linkTypes: List<LinkTypeInfo>,
    val objectTypes: List<ObjectTypeInfo>
) {

    fun linkTypeId(pdmName: String): Int? {
        val target = pdmName.normalized()
        return linkTypes.firstOrNull { it.name.normalized() == target }?.id
    }
}

data class LinkTypeInfo(val id: Int, val name: String?)

data class ObjectTypeInfo(val id: Int, val typeName: String?)

private fun String?.normalized(): String =
    this.orEmpty().lowercase().replace("\\s+".toRegex(), " ").trim()
