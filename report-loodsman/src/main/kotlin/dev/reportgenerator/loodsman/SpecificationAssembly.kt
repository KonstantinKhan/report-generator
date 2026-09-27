package dev.reportgenerator.loodsman

import dev.reportgenerator.api.ItemDto

internal data class ChildLink(val idLink: Int, val idChild: Int, val idType: Int)

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
    val kind = mapItemKind(typeNameById[child.idType]) ?: return@mapNotNull null
    val designation = designationByObjectId[child.idChild]
        ?: throw LoodsmanApiException("Attribute 'Обозначение' is missing for object ${child.idChild}")
    val name = nameByObjectId[child.idChild] ?: designation
    val quantity = quantityByLinkId[child.idLink]
        ?: throw LoodsmanApiException("Attribute 'Количество' is missing for link ${child.idLink}")
    ItemDto(designation = designation, name = name, kind = kind, quantity = quantity)
}
