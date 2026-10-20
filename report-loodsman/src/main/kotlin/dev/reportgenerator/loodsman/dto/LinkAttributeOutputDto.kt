package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class LinkAttributeOutputDto(
    val id: Int,
    val name: String? = null,
    val value: String? = null,
    val type: Int,
    val measureGuid: String? = null,
    val measure: String? = null,
    val unitGuid: String? = null,
    val unit: String? = null,
    val accessLevel: Int,
    val baseValue: String? = null,
    val location: String? = null,
    val dependentLinkAttributeBindingRuleId: Int? = null,
    val independentLinkAttributeBindingRuleId: Int? = null
)
