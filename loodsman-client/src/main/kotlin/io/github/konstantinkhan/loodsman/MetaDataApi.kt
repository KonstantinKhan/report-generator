package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkListOutputDto
import io.github.konstantinkhan.loodsman.dto.TypeListOutputDto
import io.ktor.client.plugins.timeout

private const val TYPE_LIST_REQUEST_TIMEOUT_MS = 60_000L

class MetaDataApi internal constructor(
    private val http: SessionClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/MetaData"

    suspend fun typeList(): List<TypeListOutputDto> =
        http.get("$blockBaseUrl/get-type-list") {
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }

    suspend fun linkList(): List<GetLinkListOutputDto> =
        http.get("$blockBaseUrl/get-link-list") {
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }

    suspend fun cacheVersion(): Long =
        http.get("$blockBaseUrl/cache/version") {
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }
}
