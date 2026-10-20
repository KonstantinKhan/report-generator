package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.DatabaseOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class AuthApi internal constructor(
    private val client: HttpClient,
    private val http: SessionClient,
    baseUrl: String,
) {

    private val blockBaseUrl = "$baseUrl/api/v4/Auth"

    suspend fun databases(): List<DatabaseOutputDto> =
        client.get("$blockBaseUrl/databases").body()

    suspend fun isAdmin(): Boolean =
        http.get("$blockBaseUrl/is-admin")
}
