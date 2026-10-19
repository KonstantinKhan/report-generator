package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class TypeListOutputDto(
    val id: Int,
    val typeName: String? = null,
    val attrName: String? = null,
    val document: Int = 0
)
