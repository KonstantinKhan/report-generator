package io.github.konstantinkhan.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetOriginalLinkAttributesOutputDto(
    val id: Int,
    val idLink: Int,
    val attrId: Int,
    val attrName: String? = null,
    val attrType: Int,
    val isSystem: Int,
    val value: String? = null,
    val originalValue: String? = null,
    val idUnit: String? = null,
    val unit: String? = null,
    val idMeasure: String? = null,
    val measure: String? = null,
    val baseValue: String? = null
)
