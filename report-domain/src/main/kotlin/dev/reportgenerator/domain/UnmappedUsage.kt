package dev.reportgenerator.domain

data class UnmappedUsage(
    override val relation: Relation.Unmapped,
    override val target: PdmNode,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage
