package dev.reportgenerator.data

enum class ItemKind { ASSEMBLY, PART, STANDARD }

data class SpecificationItem(
    val designation: String,
    val name: String,
    val kind: ItemKind,
    val quantity: Int
)

data class SpecificationData(
    val documentDesignation: String,
    val documentName: String,
    val items: List<SpecificationItem>
)
