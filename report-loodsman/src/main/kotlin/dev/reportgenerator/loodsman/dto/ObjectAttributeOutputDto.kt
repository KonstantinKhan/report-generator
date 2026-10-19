package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class ObjectAttributeOutputDto(
    val name: String? = null,
    val value: String? = null
)
