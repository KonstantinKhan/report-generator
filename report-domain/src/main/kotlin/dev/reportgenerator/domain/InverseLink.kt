package dev.reportgenerator.domain

data class InverseLink(
    val linkId: Int,
    val parentId: Int,
    val type: InverseLinkType,
    val quantity: Quantity,
    val unit: Field<String>
)
