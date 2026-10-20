package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetLinkListOutputDto(
    val id: Int,
    val name: String? = null,
    val type: Int,
    val inverseName: String? = null,
    val icon: String? = null,
    val order: Int
)
