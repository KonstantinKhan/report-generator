package dev.reportgenerator.data

enum class ItemKind { ASSEMBLY, PART, STANDARD, OTHER, MATERIAL }

data class SpecificationItem(
    val designation: String?,
    val name: String,
    val kind: ItemKind,
    val quantity: Double,
    val unit: String? = null
)

data class SpecificationData(
    val documentDesignation: String,
    val documentName: String,
    val items: List<SpecificationItem>
)
