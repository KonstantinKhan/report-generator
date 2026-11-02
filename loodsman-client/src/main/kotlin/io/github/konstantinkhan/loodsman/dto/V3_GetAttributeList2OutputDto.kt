package io.github.konstantinkhan.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class V3_GetAttributeList2OutputDto(
    val id: Int,
    val name: String? = null,
    val attrType: Int,
    val default: String? = null,
    val list: String? = null,
    val system: Int,
    val onlyListItems: Int
)
