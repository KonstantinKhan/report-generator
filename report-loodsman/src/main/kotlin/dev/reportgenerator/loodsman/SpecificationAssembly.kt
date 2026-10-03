package dev.reportgenerator.loodsman

import dev.reportgenerator.api.ItemDto

internal data class ChildLink(
    val idLink: Int,
    val idChild: Int,
    val idType: Int,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val unit: String? = null
)

internal fun mapItemKind(typeName: String?): String? = when (typeName?.trim()?.lowercase()) {
    "деталь" -> "PART"
    "сборочная единица" -> "ASSEMBLY"
    "стандартное изделие" -> "STANDARD"
    "прочее изделие" -> "OTHER"
    "материал по кд" -> "MATERIAL"
    else -> null
}

// Loodsman's "key attribute" in свойства (get-prop-objects.product) means different things
// per object type: Обозначение for Деталь/Сборочная единица, Наименование for everything else.
internal fun isDetailOrAssembly(typeName: String?): Boolean =
    typeName?.trim()?.lowercase() in setOf("деталь", "сборочная единица")

internal fun buildItems(
    children: List<ChildLink>,
    typeNameByObjectId: Map<Int, String>,
    designationByObjectId: Map<Int, String>,
    nameByObjectId: Map<Int, String>,
    quantityByLinkId: Map<Int, Double>,
    unitByLinkId: Map<Int, String?> = emptyMap(),
): List<ItemDto> = children.mapNotNull { child ->
    val typeName = typeNameByObjectId[child.idChild] ?: return@mapNotNull null
    val kind = mapItemKind(typeName) ?: return@mapNotNull null

    val isDetailOrAssembly = isDetailOrAssembly(typeName)

    // For Деталь/СЕ the key attribute (product) is Обозначение.
    // For Стандартное/Прочее/Материал the key attribute (product) IS Наименование —
    // there is no separate Обозначение for these types, so the "Обозначение" column
    // stays empty and only "Наименование" is filled.
    val productValue = designationByObjectId[child.idChild]
        ?: throw LoodsmanApiException(
            "Attribute '${if (isDetailOrAssembly) "Обозначение" else "Наименование"}' is missing for object ${child.idChild}"
        )

    val designation = if (isDetailOrAssembly) productValue else null

    val name = if (isDetailOrAssembly) {
        nameByObjectId[child.idChild] ?: productValue
    } else {
        productValue
    }

    val quantity = quantityByLinkId[child.idLink]
        ?: throw LoodsmanApiException("Attribute 'Количество' is missing for link ${child.idLink}")
    val unit = unitByLinkId[child.idLink]

    ItemDto(designation = designation, name = name, kind = kind, quantity = quantity, unit = unit)
}
