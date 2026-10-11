package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.domain.SpecificationData
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

class LoodsmanKtorPdmClientTest : ShouldSpec({
    should("fetch specification with single child and cache session") {
        val loginCallCount = AtomicInteger(0)
        val config = LoodsmanConfig(
            baseUrl = "http://localhost:8080",
            dbName = "testdb",
            username = "testuser",
            password = "testpass"
        )

        val mockEngine = MockEngine { request ->
            when {
                request.url.toString().contains("/api/v4/Auth/login") -> {
                    loginCallCount.incrementAndGet()
                    respond(
                        content = """{"sessionId":"session123"}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
                    respond(
                        content = """[
                            {"id":1,"name":"Состоит из ..."},
                            {"id":2,"name":"Документы"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-prop-objects?objectList=100") -> {
                    respond(
                        content = """[{
                            "idVersion":100,
                            "type":"Деталь",
                            "product":"DET-001",
                            "version":"1",
                            "state":"Released"
                        }]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-info-about-version-mode-3?idVersion=100") -> {
                    respond(
                        content = """[
                            {"name":"Наименование","value":"Test Part Name"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-linked-objects-for-objects") -> {
                    respond(
                        content = """[]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                else -> {
                    respond(
                        content = """{"error":"Not found"}""",
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpCookies)
        }

        val client = LoodsmanKtorPdmClient(config, httpClient)

        // First call to fetchSpecification
        val result1 = client.fetchSpecification("100")

        result1.documentDesignation shouldBe "DET-001"
        result1.documentName shouldBe "Test Part Name"
        result1.items.shouldBe(emptyList())

        loginCallCount.get() shouldBe 1

        // Second call should reuse cached session
        val result2 = client.fetchSpecification("100")

        result2.documentDesignation shouldBe "DET-001"
        result2.documentName shouldBe "Test Part Name"

        // Login should still be called only once (cached)
        loginCallCount.get() shouldBe 1
    }

    should("fetch specification with one child item") {
        val config = LoodsmanConfig(
            baseUrl = "http://localhost:8080",
            dbName = "testdb",
            username = "testuser",
            password = "testpass"
        )

        val mockEngine = MockEngine { request ->
            when {
                request.url.toString().contains("/api/v4/Auth/login") -> {
                    respond(
                        content = """{"sessionId":"session456"}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
                    respond(
                        content = """[
                            {"id":1,"name":"Состоит из ..."},
                            {"id":2,"name":"Документы"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-prop-objects?objectList=200") -> {
                    respond(
                        content = """[{
                            "idVersion":200,
                            "type":"Сборочная единица",
                            "product":"ASM-001",
                            "version":"1",
                            "state":"Released"
                        }]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-info-about-version-mode-3?idVersion=200") -> {
                    respond(
                        content = """[
                            {"name":"Наименование","value":"Test Assembly"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-prop-objects?objectList=201") -> {
                    respond(
                        content = """[{
                            "idVersion":201,
                            "type":"Деталь",
                            "product":"DET-002",
                            "version":"1",
                            "state":"Released"
                        }]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-info-about-version-mode-3?idVersion=201") -> {
                    respond(
                        content = """[
                            {"name":"Наименование","value":"Child Part"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-linked-objects-for-objects") -> {
                    respond(
                        content = """[{
                            "idLink":1001,
                            "idChild":201,
                            "idType":1,
                            "minQuantity":2.0,
                            "maxQuantity":2.0,
                            "unit":"шт"
                        }]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                else -> {
                    respond(
                        content = """{"error":"Not found"}""",
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpCookies)
        }

        val client = LoodsmanKtorPdmClient(config, httpClient)
        val result = client.fetchSpecification("200")

        result.documentDesignation shouldBe "ASM-001"
        result.documentName shouldBe "Test Assembly"
        result.items.size shouldBe 1
        result.items[0].designation shouldBe "DET-002"
        result.items[0].name shouldBe "Child Part"
        result.items[0].kind shouldBe ItemKind.PART
        result.items[0].quantity shouldBe 2.0
        result.items[0].unit shouldBe "шт"
    }

    should("throw exception on invalid documentId") {
        val config = LoodsmanConfig(
            baseUrl = "http://localhost:8080",
            dbName = "testdb",
            username = "testuser",
            password = "testpass"
        )
        val mockEngine = MockEngine { respond(content = "", status = HttpStatusCode.OK) }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpCookies)
        }
        val client = LoodsmanKtorPdmClient(config, httpClient)

        try {
            client.fetchSpecification("not-a-number")
            throw AssertionError("Expected LoodsmanApiException")
        } catch (e: LoodsmanApiException) {
            e.message shouldBe "documentId must be a numeric Loodsman versionId, got 'not-a-number'"
        }
    }
})
