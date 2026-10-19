package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.HorizontalLink
import dev.reportgenerator.domain.HorizontalLinkType
import dev.reportgenerator.domain.InverseLink
import dev.reportgenerator.domain.InverseLinkType
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.PdmDatabase
import dev.reportgenerator.domain.PdmObject
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.pdmType
import dev.reportgenerator.loodsman.dto.DatabaseOutputDto
import dev.reportgenerator.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import dev.reportgenerator.loodsman.dto.PropObjectOutputDto

internal fun DatabaseOutputDto.toDomain() = PdmDatabase(
    name = name ?: ""
)

private const val ATTR_DESIGNATION = "Обозначение"
private const val ATTR_NAME = "Наименование"
private const val ATTR_POSITION = "Позиция"

internal fun PropObjectOutputDto.toDomain(
    keyAttributeByType: Map<PdmType, String>,
    attributes: List<ObjectAttributeOutputDto>
) = PdmObject(
    id = idVersion,
    designation = designation(keyAttributeByType),
    name = name(attributes),
    type = pdmType(type),
    state = state ?: "",
    version = version ?: "",
    position = positionField(attributes)
)

private fun PropObjectOutputDto.designation(keyAttributeByType: Map<PdmType, String>): Designation {
    val isDesignation = keyAttributeByType[pdmType(type)] == ATTR_DESIGNATION
    return if (isDesignation) product?.let(Designation::Value) ?: Designation.Missing else Designation.Missing
}

private fun name(
    attributes: List<ObjectAttributeOutputDto>
): Name {
    val value = attributes.find { it.name?.trim() == ATTR_NAME }?.value
    return value?.trim()?.takeIf { it.isNotEmpty() }?.let(Name::Value) ?: Name.Missing
}

private fun positionField(
    attributes: List<ObjectAttributeOutputDto>
): Field<Int> {
    val value = attributes.find { it.name?.trim() == ATTR_POSITION }?.value
    return value?.trim()?.toInt()?.let { Field.Value(it) } ?: Field.Missing
}

internal fun PropObjectResult.toDomain(
    keyAttributeByType: Map<PdmType, String>,
    attributes: List<ObjectAttributeOutputDto>
): PdmObjectResult = when (this) {
    is PropObjectResult.Found -> PdmObjectResult.Found(value.toDomain(keyAttributeByType, attributes))
    is PropObjectResult.NotFound -> PdmObjectResult.NotFound(versionId)
}

internal fun GetLinkedObjectsForObjectsOutputDto_V4.toDirectLink(type: DirectLinkType) = DirectLink(
    linkId = idLink,
    childId = idChild,
    type = type,
    quantity = quantity(minQuantity, maxQuantity),
    unit = quantityUnitField(unit)
)

internal fun GetLinkedObjectsForObjectsOutputDto_V4.toInverseLink(type: InverseLinkType) = InverseLink(
    linkId = idLink,
    // для обратного запроса idParent - запрошенный объект, idChild - объект-владелец
    parentId = idChild,
    type = type,
    quantity = quantity(minQuantity, maxQuantity),
    unit = quantityUnitField(unit)
)

internal fun GetLinkedObjectsForObjectsOutputDto_V4.toHorizontalLink(type: HorizontalLinkType) = HorizontalLink(
    linkId = idLink,
    relatedId = idChild,
    type = type
)

private fun quantity(min: Double?, max: Double?): Quantity = when {
    min == null && max == null -> Quantity.Missing
    min == null -> Quantity.Exact(max!!)
    max == null || min == max -> Quantity.Exact(min)
    else -> Quantity.Range(min, max)
}

private fun quantityUnitField(unit: String?): Field<String> =
    unit?.trim()?.takeIf { it.isNotEmpty() }?.let { Field.Value(it) } ?: Field.Missing
