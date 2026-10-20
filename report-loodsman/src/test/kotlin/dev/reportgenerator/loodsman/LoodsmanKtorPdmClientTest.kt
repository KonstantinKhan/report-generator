package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.HorizontalLink
import dev.reportgenerator.domain.HorizontalLinkType
import dev.reportgenerator.domain.InverseLink
import dev.reportgenerator.domain.InverseLinkType
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.PdmObject
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.Quantity
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.github.konstantinkhan.loodsman.KtorLoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman

class LoodsmanKtorPdmClientTest : ShouldSpec({
    should("getDirectLinks returns composition links with correct mapping") {
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
                        content = """{"sessionId":"session789","userId":1,"isEditable":true}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
                    respond(
                        content = """[
                            {"id":1,"name":"Состоит из ...","type":0,"inverseName":"Входит в ...","order":1},
                            {"id":2,"name":"Документы","type":1,"order":2}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-linked-objects-for-objects") &&
                        request.url.toString().contains("inverse=false") &&
                        request.url.toString().contains("linksTypesIds=1") -> {
                    respond(
                        content = """[
                            {"idLink":10,"idParent":100,"idChild":200,"idLinkType":1,"linkTypeName":"Состоит из ...","idType":5,"idState":1,"minQuantity":1.0,"maxQuantity":1.0,"unit":"шт"}
                        ]""",
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

        val httpClient = HttpClient(mockEngine) { installLoodsman() }
        val client = LoodsmanKtorPdmClient(KtorLoodsmanApi(config, httpClient))

        val links = client.getDirectLinks(100)

        links.size shouldBe 1
        links[0] shouldBe DirectLink(
            linkId = 10,
            childId = 200,
            type = DirectLinkType.Composition,
            quantity = Quantity.Exact(1.0),
            unit = Field.Value("шт")
        )
    }

    should("getInverseLinks returns inverse links with correct mapping") {
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
                        content = """{"sessionId":"session789","userId":1,"isEditable":true}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
                    respond(
                        content = """[
                            {"id":1,"name":"Состоит из ...","type":0,"inverseName":"Входит в ...","order":1},
                            {"id":2,"name":"Документы","type":1,"order":2}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-linked-objects-for-objects") &&
                        request.url.toString().contains("inverse=true") &&
                        request.url.toString().contains("linksTypesIds=1") -> {
                    respond(
                        content = """[
                            {"idLink":11,"idParent":100,"idChild":300,"idLinkType":1,"linkTypeName":"Входит в ...","idType":5,"idState":1}
                        ]""",
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

        val httpClient = HttpClient(mockEngine) { installLoodsman() }
        val client = LoodsmanKtorPdmClient(KtorLoodsmanApi(config, httpClient))

        val links = client.getInverseLinks(100)

        links.size shouldBe 1
        links[0] shouldBe InverseLink(
            linkId = 11,
            parentId = 300,
            type = InverseLinkType.PartOf,
            quantity = Quantity.Missing,
            unit = Field.Missing
        )
    }

    should("getHorizontalLinks returns horizontal links with correct mapping") {
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
                        content = """{"sessionId":"session789","userId":1,"isEditable":true}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
                    respond(
                        content = """[
                            {"id":1,"name":"Состоит из ...","type":0,"inverseName":"Входит в ...","order":1},
                            {"id":2,"name":"Документы","type":1,"order":2}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/ObjectInfo/get-linked-objects-for-objects") &&
                        request.url.toString().contains("inverse=false") &&
                        request.url.toString().contains("linksTypesIds=2") -> {
                    respond(
                        content = """[
                            {"idLink":20,"idParent":100,"idChild":400,"idLinkType":2,"linkTypeName":"Документы","idType":5,"idState":1}
                        ]""",
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

        val httpClient = HttpClient(mockEngine) { installLoodsman() }
        val client = LoodsmanKtorPdmClient(KtorLoodsmanApi(config, httpClient))

        val links = client.getHorizontalLinks(100)

        links.size shouldBe 1
        links[0] shouldBe HorizontalLink(
            linkId = 20,
            relatedId = 400,
            type = HorizontalLinkType.Unused("Документы")
        )
    }

    should("return empty lists when no link types found") {
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
                        content = """{"sessionId":"session999","userId":1,"isEditable":true}""",
                        status = HttpStatusCode.OK,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                    )
                }
                request.url.toString().contains("/api/v4/MetaData/get-link-list") -> {
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

        val httpClient = HttpClient(mockEngine) { installLoodsman() }
        val client = LoodsmanKtorPdmClient(KtorLoodsmanApi(config, httpClient))

        val directLinks = client.getDirectLinks(100)
        val inverseLinks = client.getInverseLinks(100)
        val horizontalLinks = client.getHorizontalLinks(100)

        directLinks.size shouldBe 0
        inverseLinks.size shouldBe 0
        horizontalLinks.size shouldBe 0
    }

    should("getObject returns found object with attributes from info-about-version") {
        val (client, requested) = objectClient(
            propObjectsJson = """[{"idVersion":100,"type":"Деталь","document":0,"product":"DET-001",
                "version":"A","state":"Утв","locked":0,"accessLevel":1,"label":0,"hasLink":1,"mainId":100}]""",
            infoJson = """[{"name":"Наименование","value":"Болт"},{"name":"Позиция","value":"5"}]"""
        )

        client.getObject(100) shouldBe PdmObjectResult.Found(
            PdmObject(
                id = 100,
                designation = Designation.Value("DET-001"),
                name = Name.Value("Болт"),
                type = PdmType.Detail,
                state = "Утв",
                version = "A",
                position = Field.Value(5)
            )
        )
        requested shouldContain "/api/v4/ObjectInfo/get-prop-objects"
        requested shouldContain "/api/v4/ObjectInfo/get-info-about-version-mode-3"
    }

    should("getObject returns NotFound when prop-objects is empty") {
        val (client, _) = objectClient(propObjectsJson = "[]")

        client.getObject(100) shouldBe PdmObjectResult.NotFound(100)
    }

    should("getObject does not request info-about-version for not found object") {
        val (client, requested) = objectClient(propObjectsJson = "[]")

        client.getObject(100)

        requested shouldNotContain "/api/v4/ObjectInfo/get-info-about-version-mode-3"
    }
})

private fun objectClient(
    propObjectsJson: String,
    infoJson: String = "[]"
): Pair<LoodsmanKtorPdmClient, MutableList<String>> {
    val config = LoodsmanConfig(
        baseUrl = "http://localhost:8080",
        dbName = "testdb",
        username = "testuser",
        password = "testpass"
    )
    val requested = mutableListOf<String>()
    val mockEngine = MockEngine { request ->
        val path = request.url.encodedPath
        requested += path
        val content = when (path) {
            "/api/v4/Auth/login" -> """{"sessionId":"session789","userId":1,"isEditable":true}"""
            "/api/v4/MetaData/get-type-list" -> """[{"id":1,"typeName":"Деталь","attrName":"Обозначение","document":0}]"""
            "/api/v4/ObjectInfo/get-prop-objects" -> propObjectsJson
            "/api/v4/ObjectInfo/get-info-about-version-mode-3" -> infoJson
            else -> null
        }
        if (content == null) {
            respond(content = """{"error":"Not found"}""", status = HttpStatusCode.NotFound)
        } else {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headers {
                    append(HttpHeaders.ContentType, "application/json")
                }
            )
        }
    }
    val httpClient = HttpClient(mockEngine) { installLoodsman() }
    return LoodsmanKtorPdmClient(KtorLoodsmanApi(config, httpClient)) to requested
}
