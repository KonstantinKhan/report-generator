package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class ObjectAttributeOutputDto(
    val name: String? = null,
    val value: String? = null
)
