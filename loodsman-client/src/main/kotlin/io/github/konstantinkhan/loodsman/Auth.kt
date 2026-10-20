package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.DatabaseOutputDto
import io.github.konstantinkhan.loodsman.dto.LoginInputDto
import io.github.konstantinkhan.loodsman.dto.SessionOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

internal class Auth(
    private val client: HttpClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/Auth"

    internal suspend fun databases(): List<DatabaseOutputDto> =
        client.get("$blockBaseUrl/databases").body()

    internal suspend fun login(dbName: String, username: String, password: String): SessionOutputDto =
        client.post("$blockBaseUrl/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginInputDto(dbName, username, password))
        }.body()

    internal suspend fun logout() {
        client.post("$blockBaseUrl/logout")
    }

    internal suspend fun isAdmin(sessionId: String): Boolean =
        client.get("$blockBaseUrl/is-admin") {
            header("web-loodsman-session", sessionId)
        }.body()
}
