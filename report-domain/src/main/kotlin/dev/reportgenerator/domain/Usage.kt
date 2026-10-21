package dev.reportgenerator.domain

sealed interface Usage {
    val relation: Relation
    val child: PdmNode
    val attributes: Map<LinkAttributeKey, AttributeValue>
}
