package dev.reportgenerator.domain

data class CompositionUsage(
    override val target: PdmNode,
    val position: Field<Int>,
    val quantity: Quantity,
    val unit: Field<String>,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage {
    override val relation: Relation get() = Relation.Composition
}
