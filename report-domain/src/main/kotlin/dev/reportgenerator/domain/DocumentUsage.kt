package dev.reportgenerator.domain

data class DocumentUsage(
    override val target: PdmNode,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage {
    override val relation: Relation get() = Relation.Document
}
