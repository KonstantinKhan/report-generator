package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetLinkedObjectsForObjectsOutputDto_V4(
    val idLink: Int,
    val idParent: Int,
    val idChild: Int,
    val idLinkType: Int,
    val linkTypeName: String? = null,
    val idType: Int,
    val product: String? = null,
    val version: String? = null,
    val idState: Int,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val unit: String? = null
)
