package io.github.konstantinkhan.loodsman

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

fun loodsmanHttpClient(): HttpClient = HttpClient(CIO) { installLoodsman() }

/**
 * [retryDelay] — пауза между повторами; по умолчанию `kotlinx.coroutines.delay`, в тестах подменяется.
 *
 * Порядок установки важен: `HttpResponseValidator` ставится раньше `HttpRequestRetry`, поэтому retry видит
 * сырой 5xx-ответ (`retryIf`), а не уже сконвертированный `ResponseException`. `HttpTimeout` ставится после
 * retry, поэтому таймаут отсчитывается заново на каждую попытку.
 */
fun HttpClientConfig<*>.installLoodsman(retryDelay: suspend (Long) -> Unit = { delay(it) }) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpCookies)
    install(Logging) {
        level = LogLevel.INFO
    }
    HttpResponseValidator {
        handleResponseExceptionWithRequest { cause, request ->
            if (cause is CancellationException || cause is LoodsmanApiException) throw cause
            if (cause is ResponseException) {
                throw LoodsmanApiException(
                    "Loodsman API error ${cause.response.status.value} for " +
                            "${request.method.value} ${request.url}",
                    cause,
                    cause.response.status.value
                )
            }
            val reason = when (cause) {
                is UnresolvedAddressException -> "host not resolved: ${request.url.host}"
                is ConnectException -> "connection refused: ${request.url.host}:${request.url.port}"
                else -> cause.message ?: cause::class.simpleName
            }
            throw LoodsmanApiException(
                "Loodsman request failed: ${request.method.value} ${request.url}: $reason",
                cause
            )
        }
    }
    install(HttpRequestRetry) {
        maxRetries = LoodsmanRetryPolicy.MAX_RETRIES
        retryIf { request, response -> LoodsmanRetryPolicy.isRetryableStatus(request.method, response.status) }
        retryOnExceptionIf { request, cause -> LoodsmanRetryPolicy.isRetryableException(request.method, cause) }
        delayMillis(respectRetryAfterHeader = false) { retry ->
            LoodsmanRetryPolicy.logRetry(this, retry)
            LoodsmanRetryPolicy.delayMillis(retry)
        }
        delay(retryDelay)
    }
    install(HttpTimeout)
}
