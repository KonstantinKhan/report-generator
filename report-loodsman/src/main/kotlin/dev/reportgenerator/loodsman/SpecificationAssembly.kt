package dev.reportgenerator.loodsman

import dev.reportgenerator.api.ItemDto

internal data class ChildLink(
    val idLink: Int,
    val idChild: Int,
    val idType: Int,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null
)

internal fun mapItemKind(typeName: String?): String? = when (typeName?.trim()?.lowercase()) {
    "деталь" -> "PART"
    "стандартное изделие" -> "STANDARD"
    "прочее изделие" -> "OTHER"
    "материал по кд" -> "MATERIAL"
    else -> null
}

internal fun buildItems(
    children: List<ChildLink>,
    typeNameById: Map<Int, String>,
    designationByObjectId: Map<Int, String>,
    nameByObjectId: Map<Int, String>,
    quantityByLinkId: Map<Int, Int>,
): List<ItemDto> = children.mapNotNull { child ->
    val typeName = typeNameById[child.idType] ?: return@mapNotNull null
    val kind = mapItemKind(typeName) ?: return@mapNotNull null

    val isDetailOrAssembly = typeName.trim().lowercase() in setOf("деталь", "сборочная единица")

    // Only use designation for details and assemblies, use name otherwise
    val designation = if (isDetailOrAssembly) {
        designationByObjectId[child.idChild]
            ?: throw LoodsmanApiException("Attribute 'Обозначение' is missing for object ${child.idChild}")
    } else {
        nameByObjectId[child.idChild]
            ?: throw LoodsmanApiException("Attribute 'Наименование' is missing for object ${child.idChild}")
    }

    val name = if (isDetailOrAssembly) {
        nameByObjectId[child.idChild] ?: designation
    } else {
        designation
    }

    val quantity = quantityByLinkId[child.idLink]
        ?: throw LoodsmanApiException("Attribute 'Количество' is missing for link ${child.idLink}")

    ItemDto(designation = designation, name = name, kind = kind, quantity = quantity)
}
