package dev.reportgenerator.domain

data class StructureQuery(
    val rootId: ProductId,
    val depth: Int = Int.MAX_VALUE,
    val relations: Set<Relation> = setOf(Relation.Composition),
    val attributes: Set<AttributeKey> = emptySet(),
    val linkAttributes: Set<LinkAttributeKey> = emptySet(),
    val includeUnmappedAttributes: Boolean = false
)
