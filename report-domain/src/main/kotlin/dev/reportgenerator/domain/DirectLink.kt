package dev.reportgenerator.domain

data class DirectLink(
    val linkId: Int,
    val childId: Int,
    val type: DirectLinkType,
    val quantity: Quantity,
    val unit: Field<String>
)
