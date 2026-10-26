package dev.reportgenerator.domain

sealed interface Usage {
    val relation: Relation
    val target: PdmNode
    val attributes: Map<LinkAttributeKey, AttributeValue>
}
