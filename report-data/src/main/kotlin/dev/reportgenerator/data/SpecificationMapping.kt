package dev.reportgenerator.data

import dev.reportgenerator.api.SpecificationDto

fun mapToSpecificationData(
    dto: SpecificationDto,
    documentDesignation: String,
    documentName: String
): SpecificationData =
    SpecificationData(
        documentDesignation = documentDesignation,
        documentName = documentName,
        items = dto.items.map { item ->
            SpecificationItem(
                designation = item.designation,
                name = item.name,
                kind = mapKind(item.kind),
                quantity = item.quantity
            )
        }
    )

private fun mapKind(raw: String): ItemKind = when (raw.uppercase()) {
    "ASSEMBLY" -> ItemKind.ASSEMBLY
    "STANDARD" -> ItemKind.STANDARD
    "MATERIAL" -> ItemKind.MATERIAL
    else -> ItemKind.PART
}
