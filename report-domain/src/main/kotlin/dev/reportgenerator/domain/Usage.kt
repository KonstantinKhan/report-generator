package dev.reportgenerator.domain

sealed interface Usage {
    val relation: Relation
    val child: ProductNode
    val attributes: Map<LinkAttributeKey, AttributeValue>
}
