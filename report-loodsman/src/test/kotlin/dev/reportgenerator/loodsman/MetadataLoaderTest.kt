package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger

private const val VERSION_PATH = "/api/v4/MetaData/cache/version"
private const val TYPE_LIST_PATH = "/api/v4/MetaData/get-type-list"
private const val LINK_LIST_PATH = "/api/v4/MetaData/get-link-list"
private const val ATTRIBUTE_LIST_PATH = "/api/v4/MetaData/get-attribute-list-2"
private const val TYPES_LINKS_PATH = "/api/v4/MetaDataCustom/types-links"
private const val LOGIN_JSON = """{"sessionId":"s1","userId":1,"isEditable":true}"""
private const val VERSION_JSON = "7"
private const val TYPE_LIST_JSON = """[{"id":1,"typeName":"Деталь","attrName":"Обозначение","document":0},""" +
    """{"id":2,"typeName":"Сборочная единица","attrName":"Обозначение","document":0}]"""
private const val LINK_LIST_JSON = """[{"id":10,"name":"Состоит из ...","type":0,"order":1},{"id":11,"name":"Документы","type":1,"order":2}]"""
private const val TYPES_LINKS_JSON = """[{"typeId1":1,"typeId2":2,"idLinkType":10,"direction":1},""" +
    """{"typeId1":2,"typeId2":1,"idLinkType":11,"direction":0}]"""
private const val ATTRIBUTE_LIST_JSON = """[{"id":1,"name":" Масса ","attrType":2,"system":0,"onlyListItems":0},""" +
    """{"id":2,"name":"Наименование","attrType":1,"system":0,"onlyListItems":0},""" +
    """{"id":3,"name":null,"attrType":1,"system":1,"onlyListItems":0},""" +
    """{"id":4,"name":"   ","attrType":1,"system":0,"onlyListItems":0}]"""
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

    should("load snapshot with all link types marked horizontal by type, object types, attribute names, type links and cache version") {
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                VERSION_PATH -> respond(VERSION_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPE_LIST_PATH -> respond(TYPE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                ATTRIBUTE_LIST_PATH -> respond(ATTRIBUTE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPES_LINKS_PATH -> respond(TYPES_LINKS_JSON, HttpStatusCode.OK, JSON_HEADERS)
                else -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        val snapshot = MetadataLoader.load(api)

        snapshot shouldBe MetadataSnapshot(
            linkTypes = listOf(
                LinkTypeInfo(id = 10, name = "Состоит из ...", horizontal = false),
                LinkTypeInfo(id = 11, name = "Документы", horizontal = true),
            ),
            objectTypes = listOf(
                ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Обозначение"),
                ObjectTypeInfo(id = 2, typeName = "Сборочная единица", keyAttribute = "Обозначение"),
            ),
            attributeNames = setOf(" Масса ", "Наименование", "   "),
            version = 7,
            typeLinks = listOf(
                TypeLinkInfo(typeId1 = 1, typeId2 = 2, idLinkType = 10, direction = 1),
                TypeLinkInfo(typeId1 = 2, typeId2 = 1, idLinkType = 11, direction = 0),
            ),
            attributeIds = mapOf(" Масса " to listOf(1), "Наименование" to listOf(2), "   " to listOf(4)),
        )
    }

    should("keep key attribute of object types as is and map empty key attribute to null") {
        val typeList = """[{"id":1,"typeName":"Деталь","attrName":"  Обозначение ","document":0},""" +
            """{"id":2,"typeName":"Комплект","attrName":"","document":0},""" +
            """{"id":3,"typeName":"Материал по КД","attrName":null,"document":0}]"""
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                VERSION_PATH -> respond(VERSION_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPE_LIST_PATH -> respond(typeList, HttpStatusCode.OK, JSON_HEADERS)
                ATTRIBUTE_LIST_PATH -> respond(ATTRIBUTE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPES_LINKS_PATH -> respond(TYPES_LINKS_JSON, HttpStatusCode.OK, JSON_HEADERS)
                else -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        val snapshot = MetadataLoader.load(api)

        snapshot.objectTypes shouldBe listOf(
            ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "  Обозначение "),
            ObjectTypeInfo(id = 2, typeName = "Комплект", keyAttribute = null),
            ObjectTypeInfo(id = 3, typeName = "Материал по КД", keyAttribute = null),
        )
    }

    should("read cache version before types, links and attributes") {
        val requested = ConcurrentLinkedQueue<String>()
        val api = loodsmanApi { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                VERSION_PATH -> respond(VERSION_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPE_LIST_PATH -> respond(TYPE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                ATTRIBUTE_LIST_PATH -> respond(ATTRIBUTE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPES_LINKS_PATH -> respond(TYPES_LINKS_JSON, HttpStatusCode.OK, JSON_HEADERS)
                else -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
            }
        }

        MetadataLoader.load(api)

        requested.first() shouldBe VERSION_PATH
        requested.toList().drop(1) shouldContainExactlyInAnyOrder listOf(
            TYPE_LIST_PATH,
            LINK_LIST_PATH,
            ATTRIBUTE_LIST_PATH,
            TYPES_LINKS_PATH,
        )
    }

    should("start all four metadata requests in parallel") {
        // Все обработчики ждут друг друга: при последовательной загрузке первый зависнет и withTimeout упадёт.
        val allStarted = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                VERSION_PATH -> respond(VERSION_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPE_LIST_PATH, LINK_LIST_PATH, ATTRIBUTE_LIST_PATH, TYPES_LINKS_PATH -> {
                    if (started.incrementAndGet() == 4) allStarted.complete(Unit)
                    allStarted.await()
                    when (request.url.encodedPath) {
                        TYPE_LIST_PATH -> respond(TYPE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                        LINK_LIST_PATH -> respond(LINK_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                        TYPES_LINKS_PATH -> respond(TYPES_LINKS_JSON, HttpStatusCode.OK, JSON_HEADERS)
                        else -> respond(ATTRIBUTE_LIST_JSON, HttpStatusCode.OK, JSON_HEADERS)
                    }
                }
                else -> error("Unexpected request ${request.url}")
            }
        }

        val snapshot = withTimeout(5.seconds) { MetadataLoader.load(api) }

        snapshot.linkTypes.map { it.id } shouldBe listOf(10, 11)
        snapshot.attributeNames shouldBe setOf(" Масса ", "Наименование", "   ")
        snapshot.typeLinks.size shouldBe 2
        started.get() shouldBe 4
    }

    should("not load types and links when the version request fails") {
        val requested = ConcurrentLinkedQueue<String>()
        val api = loodsmanApi { request ->
            requested += request.url.encodedPath
            when (request.url.encodedPath) {
                VERSION_PATH -> respond("boom", HttpStatusCode.InternalServerError)
                else -> error("data must not be requested after version failure: ${request.url}")
            }
        }

        val error = shouldThrow<LoodsmanApiException> { withTimeout(5.seconds) { MetadataLoader.load(api) } }

        error.status shouldBe 500
        requested.toList() shouldBe listOf(VERSION_PATH)
    }

    should("propagate the failure of one request and cancel the others") {
        val linkListCancelled = CompletableDeferred<Unit>()
        val api = loodsmanApi { request ->
            when (request.url.encodedPath) {
                VERSION_PATH -> respond(VERSION_JSON, HttpStatusCode.OK, JSON_HEADERS)
                TYPE_LIST_PATH -> respond("boom", HttpStatusCode.InternalServerError)
                else -> try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    linkListCancelled.complete(Unit)
                    throw e
                }
            }
        }

        val error = shouldThrow<LoodsmanApiException> { withTimeout(5.seconds) { MetadataLoader.load(api) } }

        error.status shouldBe 500
        withTimeout(5.seconds) { linkListCancelled.await() }
    }
})
