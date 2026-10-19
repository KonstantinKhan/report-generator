package dev.reportgenerator.domain

data class Usage(
    val relation: Relation,
    val child: ProductNode,
    val position: Field<Int>,
    val quantity: Quantity,
    val unit: Field<String>,
    val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
)
