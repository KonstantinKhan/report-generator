package dev.reportgenerator.data

// Declaration order = group order of GOST R 2.106-2019 p.1 (the template's groupBy.order repeats it).
// SOFTWARE is a stub: no Loodsman type is mapped to it yet.
enum class ItemKind { DOCUMENTATION, COMPLEX, ASSEMBLY, PART, SOFTWARE, STANDARD, OTHER, MATERIAL, SET }

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
