package dev.reportgenerator.domain

data class ProductNode(
    val id: ProductId,
    val kind: ProductKind,
    val designation: Designation,
    val name: Name,
    val attributes: Map<AttributeKey, AttributeValue> = emptyMap(),
    val usages: List<Usage> = emptyList()
) {
    val version: AttributeValue? get() = attributes[AttributeKey.Version]

    val state: AttributeValue? get() = attributes[AttributeKey.State]
}
