package io.github.konstantinkhan.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class GetTypesLinksOutputDto(
    val typeId1: Int,
    val typeId2: Int,
    val idLinkType: Int,
    /** 1 - только прямое, -1 - только обратное, 0 - оба. */
    val direction: Int
)
