package dev.reportgenerator.data

import dev.reportgenerator.api.SpecificationDto

fun mapToSpecificationData(dto: SpecificationDto): SpecificationData =
    SpecificationData(
        documentDesignation = dto.documentDesignation,
        documentName = dto.documentName,
        items = dto.items.map { item ->
            SpecificationItem(
                designation = item.designation,
                name = item.name,
                kind = mapKind(item.kind),
                quantity = item.quantity,
                unit = item.unit
            )
        }
    )

private fun mapKind(raw: String): ItemKind = when (raw.uppercase()) {
    "ASSEMBLY" -> ItemKind.ASSEMBLY
    "STANDARD" -> ItemKind.STANDARD
    "OTHER" -> ItemKind.OTHER
    "MATERIAL" -> ItemKind.MATERIAL
    else -> ItemKind.PART
}
