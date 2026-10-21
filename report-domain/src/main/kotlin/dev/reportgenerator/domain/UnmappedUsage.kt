package dev.reportgenerator.domain

data class UnmappedUsage(
    override val relation: Relation.Unmapped,
    override val child: PdmNode,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage
