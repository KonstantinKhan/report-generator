package io.github.konstantinkhan.loodsman

import io.ktor.client.HttpClient

class LoodsmanApi(
    config: LoodsmanConfig,
    httpClient: HttpClient,
) {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val sessions = SessionHolder(Auth(httpClient, baseUrl), config)
    private val http = SessionClient(httpClient, sessions)

    val auth = AuthApi(httpClient, http, baseUrl)
    val objectInfo = ObjectInfoApi(http, baseUrl)
    val metaData = MetaDataApi(http, baseUrl)

    suspend fun logout() = sessions.close()
}
