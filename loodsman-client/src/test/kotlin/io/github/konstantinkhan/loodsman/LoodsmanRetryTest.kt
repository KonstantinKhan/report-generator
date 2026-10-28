package io.github.konstantinkhan.loodsman

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import java.net.ConnectException

private const val LOGIN_PATH = "/api/v4/Auth/login"
private const val LOGOUT_PATH = "/api/v4/Auth/logout"
private const val TYPE_LIST_PATH = "/api/v4/MetaData/get-type-list"
private const val TYPE_LIST_JSON = """[{"id":1,"typeName":"Деталь","attrName":"Обозначение изделия","document":0}]"""

private val testConfig = LoodsmanConfig(
    baseUrl = "http://localhost:8080/",
    dbName = "testdb",
    username = "testuser",
    password = "testpass",
)

private typealias RetryRoute = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

private class Recorder {
    val requests = mutableListOf<HttpRequestData>()
    val delays = mutableListOf<Long>()

    fun count(path: String): Int = requests.count { it.url.encodedPath == path }
}

private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.session(n: Int): HttpResponseData =
    reply("""{"sessionId":"s$n","userId":1,"isEditable":true}""")

private fun timeoutError(request: HttpRequestData) = HttpRequestTimeoutException(request.url.toString(), null)

private fun retryApi(recorder: Recorder, route: RetryRoute): LoodsmanApi {
    val engine = MockEngine { request ->
        recorder.requests += request
        route(request)
    }
    val client = HttpClient(engine) {
        installLoodsman(retryDelay = { recorder.delays += it })
    }
    return LoodsmanApi(testConfig, client)
}

class LoodsmanRetryTest : ShouldSpec({

    should("retry GET after two timeouts and succeed on the third attempt") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(1)
                TYPE_LIST_PATH -> if (rec.count(TYPE_LIST_PATH) <= 2) throw timeoutError(request) else reply(TYPE_LIST_JSON)
                else -> error("unexpected ${request.url}")
            }
        }

        api.metaData.typeList().single().typeName shouldBe "Деталь"

        rec.count(TYPE_LIST_PATH) shouldBe 3
        rec.count(LOGIN_PATH) shouldBe 1
        rec.delays shouldBe listOf(1_000L, 2_000L)
    }

    should("throw after exhausting all attempts on persistent timeouts") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(1)
                TYPE_LIST_PATH -> throw timeoutError(request)
                else -> error("unexpected ${request.url}")
            }
        }

        val error = shouldThrow<LoodsmanApiException> { api.metaData.typeList() }

        error.message shouldContain "Loodsman request failed: GET"
        error.status shouldBe null
        rec.count(TYPE_LIST_PATH) shouldBe 4
        rec.delays shouldBe listOf(1_000L, 2_000L, 4_000L)
    }

    should("retry GET on connection refused") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(1)
                TYPE_LIST_PATH -> if (rec.count(TYPE_LIST_PATH) == 1) throw ConnectException("Connection refused") else reply(TYPE_LIST_JSON)
                else -> error("unexpected ${request.url}")
            }
        }

        api.metaData.typeList().single().id shouldBe 1

        rec.count(TYPE_LIST_PATH) shouldBe 2
    }

    listOf(502, 503, 504).forEach { status ->
        should("retry GET on $status and succeed") {
            val rec = Recorder()
            val api = retryApi(rec) { request ->
                when (request.url.encodedPath) {
                    LOGIN_PATH -> session(1)
                    TYPE_LIST_PATH -> if (rec.count(TYPE_LIST_PATH) == 1) {
                        reply("", HttpStatusCode.fromValue(status))
                    } else {
                        reply(TYPE_LIST_JSON)
                    }
                    else -> error("unexpected ${request.url}")
                }
            }

            api.metaData.typeList().single().id shouldBe 1

            rec.count(TYPE_LIST_PATH) shouldBe 2
            rec.delays shouldBe listOf(1_000L)
        }
    }

    should("not retry GET on 404") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(1)
                TYPE_LIST_PATH -> reply("", HttpStatusCode.NotFound)
                else -> error("unexpected ${request.url}")
            }
        }

        val error = shouldThrow<LoodsmanApiException> { api.metaData.typeList() }

        error.status shouldBe 404
        rec.count(TYPE_LIST_PATH) shouldBe 1
        rec.delays.shouldBeEmpty()
    }

    should("expire session on 401 once without retrying GET inside the client") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(rec.count(LOGIN_PATH))
                TYPE_LIST_PATH -> if (rec.count(TYPE_LIST_PATH) == 1) {
                    reply("", HttpStatusCode.Unauthorized)
                } else {
                    reply(TYPE_LIST_JSON)
                }
                else -> error("unexpected ${request.url}")
            }
        }

        api.metaData.typeList().single().id shouldBe 1

        rec.count(TYPE_LIST_PATH) shouldBe 2
        rec.count(LOGIN_PATH) shouldBe 2
        rec.delays.shouldBeEmpty()
    }

    should("not retry POST login on timeout") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> throw timeoutError(request)
                else -> error("unexpected ${request.url}")
            }
        }

        val error = shouldThrow<LoodsmanApiException> { api.metaData.typeList() }

        error.message shouldContain "POST"
        rec.count(LOGIN_PATH) shouldBe 1
        rec.delays.shouldBeEmpty()
    }

    should("not retry POST logout even on 503") {
        val rec = Recorder()
        val api = retryApi(rec) { request ->
            when (request.url.encodedPath) {
                LOGIN_PATH -> session(1)
                TYPE_LIST_PATH -> reply(TYPE_LIST_JSON)
                LOGOUT_PATH -> reply("", HttpStatusCode.ServiceUnavailable)
                else -> error("unexpected ${request.url}")
            }
        }
        api.metaData.typeList()

        val error = shouldThrow<LoodsmanApiException> { api.logout() }

        error.status shouldBe 503
        rec.count(LOGOUT_PATH) shouldBe 1
        rec.delays.shouldBeEmpty()
    }

    should("apply request timeout to every attempt separately") {
        var attempts = 0
        val engine = MockEngine { _ ->
            attempts++
            // Первая попытка «зависает» дольше таймаута запроса и должна быть прервана HttpTimeout.
            if (attempts == 1) delay(5_000)
            reply("ok")
        }
        val client = HttpClient(engine) {
            installLoodsman(retryDelay = {})
        }

        val body = client.get("http://localhost:8080/ping") {
            timeout { requestTimeoutMillis = 100 }
        }.bodyAsText()

        body shouldBe "ok"
        attempts shouldBe 2
    }
})
