package io.github.konstantinkhan.loodsman

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger

private const val SESSION_HEADER = "web-loodsman-session"
private const val LOGIN_PATH = "/api/v4/Auth/login"

private val testConfig = LoodsmanConfig(
    baseUrl = "http://localhost:8080/",
    dbName = "testdb",
    username = "testuser",
    password = "testpass",
)

private typealias Route = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun HttpRequestData.session(): String? = headers[SESSION_HEADER]

private fun ktorApi(
    requests: MutableList<HttpRequestData>,
    logins: AtomicInteger,
    route: Route,
): LoodsmanApi {
    val engine = MockEngine { request ->
        requests += request
        if (request.url.encodedPath == LOGIN_PATH) {
            reply("""{"sessionId":"s${logins.incrementAndGet()}","userId":1,"isEditable":true}""")
        } else {
            route(request)
        }
    }
    return LoodsmanApi(testConfig, HttpClient(engine) { installLoodsman() })
}

private const val TYPE_LIST_JSON = """[{"id":1,"typeName":"Деталь","attrName":"Обозначение изделия","document":0}]"""
private const val LINK_LIST_JSON = """[{"id":2,"name":"Состоит из ...","type":0,"inverseName":"Входит в ...","order":1}]"""
private const val ATTRIBUTE_LIST_JSON = """[{"id":7,"name":"Масса","attrType":2,"default":null,"list":null,"system":0,"onlyListItems":0,"accessLevel":1}]"""
private const val PROP_OBJECTS_JSON = """[{"idVersion":100,"type":"Деталь","document":0,"product":"DET-001",
    "version":"1","state":"Released","locked":0,"accessLevel":1,"label":0,"hasLink":1,"mainId":100}]"""
private const val OBJECT_INFO_JSON = """[{"name":"Наименование","value":"Test"}]"""
private const val LINKED_OBJECTS_JSON = """[{"idLink":7,"idParent":10,"idChild":20,"idLinkType":1,
    "linkTypeName":"Состоит из ...","idType":3,"product":"SUB-1","version":"1","idState":1,
    "minQuantity":2.0,"maxQuantity":2.0,"unit":"шт"}]"""
private const val TYPES_LINKS_JSON = """[{"typeId1":1,"typeId2":2,"idLinkType":3,"direction":1},{"typeId1":2,"typeId2":1,"idLinkType":4,"direction":-1}]"""
private const val LINK_ATTRIBUTES_JSON ="""[{"id":5,"name":"Позиция","value":"1","type":0,"accessLevel":1}]"""
private const val REPLACEMENT_VARIANTS_JSON = """[{"idGroupChange":240,"groupChangeName":"Замена","groupChangeType":2,
    "groupInCurrentConf":1,"idVariant":271,"variantName":"Основной","basic":1,"variantInCurrentConf":1,"idLink":10045,
    "idParent":4757,"linkType":"Состоит из ...","idVersion":5001,"idType":3,"idState":1,"product":"DET-5","version":"1",
    "idLock":0,"accessLevel":1,"label":0,"labelName":null}]"""

class LoodsmanApiTest : ShouldSpec({

    should("list databases without session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("""[{"name":"db1"}]""") }

        api.auth.databases().map { it.name } shouldBe listOf("db1")

        val request = requests.single()
        request.method shouldBe HttpMethod.Get
        request.url.encodedPath shouldBe "/api/v4/Auth/databases"
        request.session() shouldBe null
        logins.get() shouldBe 0
    }

    should("check admin with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("true") }

        api.auth.isAdmin() shouldBe true

        val request = requests.last { it.url.encodedPath == "/api/v4/Auth/is-admin" }
        request.method shouldBe HttpMethod.Get
        request.session() shouldBe "s1"
    }

    should("fetch type list with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(TYPE_LIST_JSON) }

        val types = api.metaData.typeList()

        types.single().also {
            it.id shouldBe 1
            it.typeName shouldBe "Деталь"
            it.attrName shouldBe "Обозначение изделия"
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/MetaData/get-type-list" }
        request.method shouldBe HttpMethod.Get
        request.session() shouldBe "s1"
    }

    should("fetch link list with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(LINK_LIST_JSON) }

        val links = api.metaData.linkList()

        links.single().also {
            it.id shouldBe 2
            it.name shouldBe "Состоит из ..."
            it.inverseName shouldBe "Входит в ..."
            it.order shouldBe 1
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/MetaData/get-link-list" }
        request.method shouldBe HttpMethod.Get
        request.session() shouldBe "s1"
    }

    should("fetch types links with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(TYPES_LINKS_JSON) }

        val links = api.metaDataCustom.typesLinks()

        links.size shouldBe 2
        links[0].also {
            it.typeId1 shouldBe 1
            it.typeId2 shouldBe 2
            it.idLinkType shouldBe 3
            it.direction shouldBe 1
        }
        links[1].direction shouldBe -1
        val request = requests.last { it.url.encodedPath == "/api/v4/MetaDataCustom/types-links" }
        request.method shouldBe HttpMethod.Get
        request.session() shouldBe "s1"
    }

    should("fetch metadata cache version as plain int64") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("9007199254740993") }

        api.metaData.cacheVersion() shouldBe 9007199254740993L

        val request = requests.last { it.url.encodedPath == "/api/v4/MetaData/cache/version" }
        request.method shouldBe HttpMethod.Get
        request.session() shouldBe "s1"
    }

    should("fetch attribute list with mode query and session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(ATTRIBUTE_LIST_JSON) }

        val attributes = api.metaData.attributeList(mode = 1)

        attributes.single().also {
            it.id shouldBe 7
            it.name shouldBe "Масса"
            it.attrType shouldBe 2
            it.system shouldBe 0
            it.onlyListItems shouldBe 0
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/MetaData/get-attribute-list-2" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["mode"] shouldBe "1"
        request.session() shouldBe "s1"
    }

    should("fetch prop objects with comma separated version ids") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(PROP_OBJECTS_JSON) }

        val objects = api.objectInfo.propObjects(listOf(100, 101))

        objects.single().also {
            it.idVersion shouldBe 100
            it.product shouldBe "DET-001"
            it.state shouldBe "Released"
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-prop-objects" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["objectList"] shouldBe "100,101"
        request.url.parameters["parameters"] shouldBe "0"
        request.session() shouldBe "s1"
    }

    should("fetch info about version with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(OBJECT_INFO_JSON) }

        api.objectInfo.infoAboutVersion(100).single().value shouldBe "Test"

        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-info-about-version-mode-3" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["idVersion"] shouldBe "100"
        request.session() shouldBe "s1"
    }

    should("fetch linked objects for a single object id") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(LINKED_OBJECTS_JSON) }

        val links = api.objectInfo.linkedObjects(objectIds = listOf(10), linkTypeIds = listOf(1))

        links.single().also {
            it.idChild shouldBe 20
            it.minQuantity shouldBe 2.0
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-linked-objects-for-objects" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["objectsIds"] shouldBe "10"
        request.url.parameters["linksTypesIds"] shouldBe "1"
        request.url.parameters["inverse"] shouldBe "false"
        request.session() shouldBe "s1"
    }

    should("pass several object and link type ids comma separated to linked objects") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("[]") }

        api.objectInfo.linkedObjects(objectIds = listOf(10, 11), linkTypeIds = listOf(3, 4), inverse = true)

        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-linked-objects-for-objects" }
        request.url.parameters["objectsIds"] shouldBe "10,11"
        request.url.parameters["linksTypesIds"] shouldBe "3,4"
        request.url.parameters["inverse"] shouldBe "true"
    }

    should("fetch link attributes with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(LINK_ATTRIBUTES_JSON) }

        api.objectInfo.linkAttributes(5).single().also {
            it.id shouldBe 5
            it.value shouldBe "1"
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-link-attributes" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["linkId"] shouldBe "5"
        request.session() shouldBe "s1"
    }

    should("fetch replacement variants of an object with session") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(REPLACEMENT_VARIANTS_JSON) }

        api.objectInfo.replacementVariants(4757).single().also {
            it.idGroupChange shouldBe 240
            it.groupChangeType shouldBe 2
            it.idVariant shouldBe 271
            it.basic shouldBe 1
            it.idLink shouldBe 10045
        }
        val request = requests.last { it.url.encodedPath == "/api/v4/ObjectInfo/get-info-about-version-mode-20" }
        request.method shouldBe HttpMethod.Get
        request.url.parameters["idVersion"] shouldBe "4757"
        request.session() shouldBe "s1"
    }

    should("login once for several calls") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { request ->
            when (request.url.encodedPath) {
                "/api/v4/MetaData/get-type-list" -> reply(TYPE_LIST_JSON)
                "/api/v4/MetaData/get-link-list" -> reply(LINK_LIST_JSON)
                else -> reply(PROP_OBJECTS_JSON)
            }
        }

        api.metaData.typeList()
        api.metaData.linkList()
        api.objectInfo.propObjects(listOf(100))

        logins.get() shouldBe 1
        requests.count { it.url.encodedPath == LOGIN_PATH } shouldBe 1
        requests.filter { it.url.encodedPath != LOGIN_PATH }.map { it.session() }.distinct() shouldBe listOf("s1")
    }

    should("check admin logs in once before is-admin call") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("true") }

        api.auth.isAdmin() shouldBe true

        logins.get() shouldBe 1
        requests.map { it.url.encodedPath } shouldBe listOf(LOGIN_PATH, "/api/v4/Auth/is-admin")
        requests.last().session() shouldBe "s1"
    }

    should("list databases without session header and without login after session exists") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { request ->
            if (request.url.encodedPath == "/api/v4/Auth/databases") reply("""[{"name":"db1"}]""")
            else reply(TYPE_LIST_JSON)
        }

        api.metaData.typeList()
        api.auth.databases().map { it.name } shouldBe listOf("db1")

        logins.get() shouldBe 1
        requests.last { it.url.encodedPath == "/api/v4/Auth/databases" }.session() shouldBe null
    }

    should("share one session between auth, objectInfo and metaData groups") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { request ->
            when (request.url.encodedPath) {
                "/api/v4/Auth/is-admin" -> reply("true")
                "/api/v4/ObjectInfo/get-prop-objects" -> reply(PROP_OBJECTS_JSON)
                else -> reply(TYPE_LIST_JSON)
            }
        }

        api.auth.isAdmin()
        api.objectInfo.propObjects(listOf(100))
        api.metaData.typeList()

        logins.get() shouldBe 1
        requests.filter { it.url.encodedPath != LOGIN_PATH }.map { it.session() }.distinct() shouldBe listOf("s1")
    }

    listOf(401, 419).forEach { status ->
        should("re-login and retry once on $status") {
            val requests = mutableListOf<HttpRequestData>()
            val logins = AtomicInteger()
            val api = ktorApi(requests, logins) { request ->
                if (request.session() == "s1") reply("", HttpStatusCode.fromValue(status)) else reply(TYPE_LIST_JSON)
            }

            api.metaData.typeList().single().id shouldBe 1

            logins.get() shouldBe 2
            requests.filter { it.url.encodedPath == "/api/v4/MetaData/get-type-list" }
                .map { it.session() } shouldBe listOf("s1", "s2")
        }

        should("throw LoodsmanApiException with status $status when retry fails again") {
            val requests = mutableListOf<HttpRequestData>()
            val logins = AtomicInteger()
            val api = ktorApi(requests, logins) { reply("", HttpStatusCode.fromValue(status)) }

            val error = shouldThrow<LoodsmanApiException> { api.metaData.typeList() }

            error.status shouldBe status
            logins.get() shouldBe 2
            requests.count { it.url.encodedPath == "/api/v4/MetaData/get-type-list" } shouldBe 2
        }
    }

    should("throw LoodsmanApiException with status on server error") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }

        val error = shouldThrow<LoodsmanApiException> { api.objectInfo.linkedObjects(listOf(10), listOf(1)) }

        error.status shouldBe 500
        logins.get() shouldBe 1
    }

    should("logout via auth endpoint and login again on next call") {
        val requests = mutableListOf<HttpRequestData>()
        val logins = AtomicInteger()
        val api = ktorApi(requests, logins) { reply(TYPE_LIST_JSON) }

        api.metaData.typeList()
        api.logout()

        val logout = requests.last { it.url.encodedPath == "/api/v4/Auth/logout" }
        logout.method shouldBe HttpMethod.Post

        api.metaData.typeList()

        logins.get() shouldBe 2
        requests.last { it.url.encodedPath == "/api/v4/MetaData/get-type-list" }.session() shouldBe "s2"
    }
})
