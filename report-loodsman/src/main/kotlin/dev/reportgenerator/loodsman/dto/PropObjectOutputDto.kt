package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class PropObjectOutputDto(
    val idVersion: Int,
    val type: String? = null,
    val document: Int,
    val product: String? = null,
    val version: String? = null,
    val state: String? = null,
    val locked: Int,
    val accessLevel: Int,
    val label: Int,
    val labelName: String? = null,
    val hasLink: Int,
    val mainId: Int,
    val location: String? = null
)
