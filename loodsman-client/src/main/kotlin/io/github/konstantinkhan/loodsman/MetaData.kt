package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkListOutputDto
import io.github.konstantinkhan.loodsman.dto.TypeListOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header

private const val TYPE_LIST_REQUEST_TIMEOUT_MS = 60_000L

internal class MetaData(
    private val client: HttpClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/MetaData"

    internal suspend fun typeList(sessionId: String): List<TypeListOutputDto> =
        client.get("$blockBaseUrl/get-type-list") {
            header("web-loodsman-session", sessionId)
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }.body()

    internal suspend fun linkList(sessionId: String): List<GetLinkListOutputDto> =
        client.get("$blockBaseUrl/get-link-list") {
            header("web-loodsman-session", sessionId)
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }.body()
}
