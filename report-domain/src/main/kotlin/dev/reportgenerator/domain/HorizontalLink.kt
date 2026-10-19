package dev.reportgenerator.domain

data class HorizontalLink(
    val linkId: Int,
    val relatedId: Int,
    val type: HorizontalLinkType
)
