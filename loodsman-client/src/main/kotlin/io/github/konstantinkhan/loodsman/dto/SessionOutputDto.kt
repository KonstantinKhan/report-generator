package io.github.konstantinkhan.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
data class SessionOutputDto(
    val sessionId: String? = null,
    val dbName: String? = null,
    val userId: Int,
    val checkoutId: Int? = null,
    val isEditable: Boolean
)
