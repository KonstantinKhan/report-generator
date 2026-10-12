package dev.reportgenerator.loodsman.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LoginInputDto(
    val dbName: String,
    val username: String,
    val password: String,
    val rememberMe: Boolean = false
)
