package io.github.konstantinkhan.loodsman

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header

private const val SESSION_HEADER = "web-loodsman-session"

internal class SessionClient(
    internal val client: HttpClient,
    internal val sessions: SessionHolder,
) {

    internal suspend inline fun <reified T> get(
        url: String,
        noinline request: HttpRequestBuilder.() -> Unit = {},
    ): T = sessions.withSession { sessionId ->
        client.get(url) {
            header(SESSION_HEADER, sessionId)
            request()
        }.body()
    }
}
