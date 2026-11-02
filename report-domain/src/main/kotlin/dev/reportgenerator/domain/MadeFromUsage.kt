package dev.reportgenerator.domain

data class MadeFromUsage(
    override val target: PdmNode,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage {
    override val relation: Relation get() = Relation.MadeFrom
}
