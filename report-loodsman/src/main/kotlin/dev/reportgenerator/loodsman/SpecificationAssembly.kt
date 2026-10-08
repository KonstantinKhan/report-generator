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
    "сборочный чертеж", "сборочный чертёж" -> "DOCUMENTATION"
    "комплекс" -> "COMPLEX"
    "сборочная единица" -> "ASSEMBLY"
    "деталь" -> "PART"
    // SOFTWARE (программные изделия и базы данных): Loodsman type not defined yet, nothing maps to it.
    "стандартное изделие" -> "STANDARD"
    "прочее изделие" -> "OTHER"
    "материал по кд" -> "MATERIAL"
    "комплект" -> "SET"
    else -> null
}

// Loodsman's "key attribute" (get-prop-objects.product) means different things per object type:
//  * Обозначение for Сборочный чертеж/Комплекс/Сборочная единица/Деталь/Комплект (Наименование is a separate attribute);
//  * Наименование for Стандартное изделие/Прочее изделие/Материал по КД
//    (their Обозначение for the report is the separate attribute "Обозначение изделия", not for Материал).
private val DESIGNATION_KEYED_KINDS = setOf("DOCUMENTATION", "COMPLEX", "ASSEMBLY", "PART", "SET")
private val KINDS_WITH_PRODUCT_DESIGNATION_ATTR = setOf("STANDARD", "OTHER")

internal fun isDesignationKeyed(typeName: String?): Boolean = mapItemKind(typeName) in DESIGNATION_KEYED_KINDS

// Whether the report designation of this type comes from the attribute "Обозначение изделия".
internal fun hasProductDesignationAttr(typeName: String?): Boolean =
    mapItemKind(typeName) in KINDS_WITH_PRODUCT_DESIGNATION_ATTR

// `productByObjectId` = key attribute (PropObjectDto.product). `nameByObjectId` = attribute Наименование,
// `productDesignationByObjectId` = attribute "Обозначение изделия".
internal fun buildItems(
    children: List<ChildLink>,
    typeNameByObjectId: Map<Int, String>,
    productByObjectId: Map<Int, String>,
    nameByObjectId: Map<Int, String>,
    quantityByLinkId: Map<Int, Double>,
    unitByLinkId: Map<Int, String?> = emptyMap(),
    productDesignationByObjectId: Map<Int, String> = emptyMap(),
): List<ItemDto> = children.mapNotNull { child ->
    val typeName = typeNameByObjectId[child.idChild] ?: return@mapNotNull null
    val kind = mapItemKind(typeName) ?: return@mapNotNull null

    val designationKeyed = kind in DESIGNATION_KEYED_KINDS

    val productValue = productByObjectId[child.idChild]
        ?: throw LoodsmanApiException(
            "Key attribute is missing for object ${child.idChild}"
        )

    val designation = when {
        designationKeyed -> productValue
        kind in KINDS_WITH_PRODUCT_DESIGNATION_ATTR -> productDesignationByObjectId[child.idChild]
        else -> null // Материал: Обозначение не заполняется
    }

    val name = if (designationKeyed) nameByObjectId[child.idChild] ?: productValue else productValue

    // Документация: the column "Кол." stays empty, a missing link quantity is fine.
    val quantity = quantityByLinkId[child.idLink]
        ?: if (kind == "DOCUMENTATION") 0.0
        else throw LoodsmanApiException("Attribute 'Количество' is missing for link ${child.idLink}")
    val unit = unitByLinkId[child.idLink]

    ItemDto(designation = designation, name = name, kind = kind, quantity = quantity, unit = unit)
}
