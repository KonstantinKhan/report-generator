package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkListOutputDto
import io.github.konstantinkhan.loodsman.dto.TypeListOutputDto
import io.github.konstantinkhan.loodsman.dto.V3_GetAttributeList2OutputDto
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter

// Справочник типов на живом сервере отвечает до ~60 с, поэтому таймаут с запасом.
private const val TYPE_LIST_REQUEST_TIMEOUT_MS = 180_000L
private const val ATTRIBUTE_LIST_REQUEST_TIMEOUT_MS = 60_000L

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

    /** [mode]: 0 - все атрибуты, 1 - обычные, 2 - служебные. */
    suspend fun attributeList(mode: Int = 0): List<V3_GetAttributeList2OutputDto> =
        http.get("$blockBaseUrl/get-attribute-list-2") {
            parameter("mode", mode)
            timeout { requestTimeoutMillis = ATTRIBUTE_LIST_REQUEST_TIMEOUT_MS }
        }

    suspend fun cacheVersion(): Long =
        http.get("$blockBaseUrl/cache/version") {
            timeout { requestTimeoutMillis = TYPE_LIST_REQUEST_TIMEOUT_MS }
        }
}
