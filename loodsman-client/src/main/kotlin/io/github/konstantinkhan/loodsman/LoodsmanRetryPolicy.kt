package io.github.konstantinkhan.loodsman

import io.ktor.client.plugins.HttpRetryDelayContext
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.util.logging.KtorSimpleLogger
import java.io.IOException

/**
 * Политика повторов HTTP-запросов к Loodsman.
 *
 * Повторяются только GET (идемпотентные). POST (login, logout и любые другие) не повторяются никогда.
 * Повтор при сетевом IOException (таймауты, connection refused и т.д.) и ответах 502/503/504.
 * 4xx и ошибки сериализации не повторяются.
 */
internal object LoodsmanRetryPolicy {

    /** Повторов после первой попытки: всего попыток = MAX_RETRIES + 1. */
    const val MAX_RETRIES = 3

    /** Пауза перед первым повтором; каждый следующий вдвое длиннее: 1 с, 2 с, 4 с. */
    const val BASE_DELAY_MS = 1_000L

    private val RETRYABLE_STATUSES = setOf(
        HttpStatusCode.BadGateway.value,
        HttpStatusCode.ServiceUnavailable.value,
        HttpStatusCode.GatewayTimeout.value,
    )

    private const val LOGGER_NAME = "io.github.konstantinkhan.loodsman.retry"

    private val log = KtorSimpleLogger(LOGGER_NAME)

    fun isRetryableStatus(method: HttpMethod, status: HttpStatusCode): Boolean =
        method == HttpMethod.Get && status.value in RETRYABLE_STATUSES

    fun isRetryableException(method: HttpMethod, cause: Throwable): Boolean =
        method == HttpMethod.Get && networkCause(cause) != null

    /** [retry] начинается с 1: первый повтор ждёт [BASE_DELAY_MS]. */
    fun delayMillis(retry: Int): Long = BASE_DELAY_MS shl (retry - 1)

    /**
     * Одна строка на повтор: метод, URL без query-параметров, номер попытки, причина.
     * Заголовки (в т.ч. сессию) и тело не логируются.
     */
    fun logRetry(context: HttpRetryDelayContext, retry: Int) {
        val method = context.request.method.value
        val url = context.request.url.toString().substringBefore('?')
        val reason = context.response?.let { "HTTP ${it.status.value}" }
            ?: networkCause(context.cause)?.let { it::class.simpleName }
            ?: context.cause?.let { it::class.simpleName }
            ?: "unknown"
        log.warn("Loodsman retry $retry/$MAX_RETRIES: $method $url, reason: $reason")
    }

    private fun networkCause(cause: Throwable?): Throwable? =
        generateSequence(cause) { it.cause }.firstOrNull { it is IOException }
}
