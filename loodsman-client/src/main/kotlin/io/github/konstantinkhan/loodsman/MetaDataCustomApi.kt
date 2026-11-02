package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetTypesLinksOutputDto
import io.ktor.client.plugins.timeout

private const val DEFAULT_REQUEST_TIMEOUT_MS = 60_000L

class MetaDataCustomApi internal constructor(
    private val http: SessionClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/MetaDataCustom"

    suspend fun typesLinks(): List<GetTypesLinksOutputDto> =
        http.get("$blockBaseUrl/types-links") {
            timeout { requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS }
        }
}
