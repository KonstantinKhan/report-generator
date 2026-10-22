package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

private const val TYPE_LIST_PATH = "/api/v4/MetaData/get-type-list"
private const val LINK_LIST_PATH = "/api/v4/MetaData/get-link-list"
private const val LOGIN_JSON = """{"sessionId":"s1","userId":1,"isEditable":true}"""
private const val TYPE_LIST_JSON = """[{"id":1,"typeName":"Деталь","document":0},{"id":2,"typeName":"Сборочная единица","document":0}]"""
private const val LINK_LIST_JSON = """[{"id":10,"name":"Состоит из ...","type":0,"order":1},{"id":11,"name":"Документы","type":1,"order":2}]"""
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private fun loodsmanApi(metaHandler: MockRequestHandler): LoodsmanApi {
    val engine = MockEngine { request ->
        if (request.url.encodedPath == "/api/v4/Auth/login") {
            respond(LOGIN_JSON, HttpStatusCode.OK, JSON_HEADERS)
        } else {
            metaHandler(request)
        }
    }
    val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
    return LoodsmanApi(config, HttpClient(engine) { installLoodsman() })
}

class MetadataLoaderTest : ShouldSpec({

    should("load snapshot with link types filtered by type 0 and all object types") {
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                TYPE_LIST_PATH -> respond(TYPE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                else -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        val snapshot = MetadataLoader.load(api)

        snapshot shouldBe MetadataSnapshot(
            linkTypes = listOf(LinkTypeInfo(id = 10, name = "Состоит из ...")),
            objectTypes = listOf(
                ObjectTypeInfo(id = 1, typeName = "Деталь"),
                ObjectTypeInfo(id = 2, typeName = "Сборочная единица"),
            ),
        )
    }

    should("start both metadata requests in parallel") {
        // Оба обработчика ждут друг друга: при последовательной загрузке первый зависнет и withTimeout упадёт.
        val bothStarted = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val api = loodsmanApi { request ->
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            bothStarted.await()
            when (request.url.encodedPath) {
                TYPE_LIST_PATH -> respond(TYPE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                else -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        val snapshot = withTimeout(5_000) { MetadataLoader.load(api) }

        snapshot.linkTypes.map { it.id } shouldBe listOf(10)
        started.get() shouldBe 2
    }

    should("propagate the failure of one request and cancel the other") {
        val linkListCancelled = CompletableDeferred<Unit>()
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                TYPE_LIST_PATH -> respond("boom", HttpStatusCode.InternalServerError)
                else -> try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    linkListCancelled.complete(Unit)
                    throw e
                }
            }
        }

        val error = shouldThrow<LoodsmanApiException> { withTimeout(5_000) { MetadataLoader.load(api) } }

        error.status shouldBe 500
        withTimeout(5_000) { linkListCancelled.await() }
    }
})
