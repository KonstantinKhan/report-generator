package dev.reportgenerator.loodsman

import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.api.SpecificationDto
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ASSEMBLY_COMPOSITION_LINK_NAME = "Состоит из ..."
private const val ATTR_DESIGNATION = "Обозначение"
private const val ATTR_NAME = "Наименование"
private const val ATTR_QUANTITY = "Количество"

class LoodsmanPdmClient(private val config: LoodsmanConfig) : PdmClient {

    private val baseUrl = config.baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedSessionId: String? = null
    private var cachedAssemblyLinkTypeId: Int? = null
    private var cachedTypeNameById: Map<Int, String>? = null

    override fun fetchSpecification(documentId: String): SpecificationDto {
        val versionId = documentId.toIntOrNull()
            ?: throw LoodsmanApiException("documentId must be a numeric Loodsman versionId, got '$documentId'")

        ensureMetaLoaded()
        val linkTypeId = cachedAssemblyLinkTypeId!!
        val typeNameById = cachedTypeNameById!!

        // Get prop objects (type, product, version, state)
        val propObjects = getJson<List<PropObjectDto>>(
            get("/api/v4/ObjectInfo/get-prop-objects").withQuery("objectList" to versionId.toString())
        )
        val prop = propObjects.firstOrNull()
            ?: throw LoodsmanApiException("Document not found in Loodsman: versionId=$versionId")

        // Get document attributes (name, designation)
        val docAttrs = getJson<List<ObjectAttributeDto>>(
            get("/api/v4/ObjectInfo/get-info-about-version-mode-3").withQuery("idVersion" to versionId.toString())
        )
        val docAttrMap = docAttrs.associateBy { it.name }

        val documentDesignation = prop.product?.trim()
            ?: throw LoodsmanApiException("Document not found in Loodsman: no product (designation) for versionId=$versionId")
        val documentName = docAttrMap[ATTR_NAME]?.value?.trim()
            ?: throw LoodsmanApiException("Document not found in Loodsman: no '$ATTR_NAME' for versionId=$versionId")

        // Get linked objects
        val children = getLinkedObjects(versionId, linkTypeId).map {
            ChildLink(it.idLink, it.idChild, it.idType, it.minQuantity, it.maxQuantity)
        }

        // Get designation and name for all child objects
        val designationByObjectId = HashMap<Int, String>()
        val nameByObjectId = HashMap<Int, String>()

        children.forEach { child ->
            val childProps = getJson<List<PropObjectDto>>(
                get("/api/v4/ObjectInfo/get-prop-objects").withQuery("objectList" to child.idChild.toString())
            ).firstOrNull()
                ?: throw LoodsmanApiException("Child object ${child.idChild} not found")

            designationByObjectId[child.idChild] = childProps.product?.trim()
                ?: throw LoodsmanApiException("Child object ${child.idChild} missing product (designation)")

            val childAttrs = getJson<List<ObjectAttributeDto>>(
                get("/api/v4/ObjectInfo/get-info-about-version-mode-3").withQuery("idVersion" to child.idChild.toString())
            )
            childAttrs.find { it.name == ATTR_NAME }?.value?.trim()?.let {
                nameByObjectId[child.idChild] = it
            }
        }

        // Get quantity from minQuantity/maxQuantity
        val quantityByLinkId = HashMap<Int, Int>()
        children.forEach { child ->
            val quantity = if (child.minQuantity != null && child.minQuantity == child.maxQuantity) {
                child.minQuantity.toInt()
            } else {
                child.minQuantity?.toInt() ?: child.maxQuantity?.toInt()
                    ?: throw LoodsmanApiException("Attribute 'Количество' is missing for link ${child.idLink}")
            }
            quantityByLinkId[child.idLink] = quantity
        }

        val items = buildItems(children, typeNameById, designationByObjectId, nameByObjectId, quantityByLinkId)

        return SpecificationDto(documentDesignation, documentName, items)
    }

    @Synchronized
    private fun ensureMetaLoaded() {
        if (cachedAssemblyLinkTypeId != null && cachedTypeNameById != null) return

        val linkTypes = getJson<List<LinkListEntry>>(get("/api/v4/MetaData/get-link-list"))
        cachedAssemblyLinkTypeId = linkTypes.firstOrNull { it.name?.trim() == ASSEMBLY_COMPOSITION_LINK_NAME }?.id
            ?: throw LoodsmanApiException("Link type '$ASSEMBLY_COMPOSITION_LINK_NAME' not found in Loodsman metadata")

        val types = getJson<List<TypeListEntry>>(get("/api/v4/MetaData/get-type-list").withQuery("setAlphaChannel" to "false"))
        cachedTypeNameById = types.filter { it.typeName != null }.associate { it.id to it.typeName!! }
    }

    private fun getLinkedObjects(objectId: Int, linkTypeId: Int): List<LinkedObjectDto> =
        getJson(
            get("/api/v4/ObjectInfo/get-linked-objects-for-objects").withQuery(
                "objectsIds" to objectId.toString(),
                "linksTypesIds" to linkTypeId.toString(),
                "inverse" to "false",
            )
        )


    @Synchronized
    private fun session(): String = cachedSessionId ?: login().also { cachedSessionId = it }

    private fun login(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/v4/Auth/login"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("x-loodsman-db-name", config.dbName)
            .POST(BodyPublishers.ofString(json.encodeToString(LoginRequest(config.dbName, config.username, config.password))))
            .build()
        val response = http.send(request, BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw LoodsmanApiException("Loodsman login failed with status ${response.statusCode()}: ${response.body()}")
        }
        val session = try {
            json.decodeFromString<SessionResponse>(response.body())
        } catch (e: SerializationException) {
            throw LoodsmanApiException("Malformed JSON response from Loodsman login: ${e.message}")
        }
        return session.sessionId
            ?: throw LoodsmanApiException("Loodsman login response did not contain a sessionId")
    }

    // Use web-loodsman-session header for authentication, not Authorization
    private fun authorizedRequest(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(uri)
            .header("web-loodsman-session", session())
            .header("Accept", "application/json")
            .header("x-loodsman-db-name", config.dbName)

    private fun get(path: String): RequestSpec = RequestSpec(path)

    private fun post(path: String): RequestSpec = RequestSpec(path)

    private inner class RequestSpec(private val path: String) {
        private val query = mutableListOf<Pair<String, String>>()

        fun withQuery(vararg params: Pair<String, String>): RequestSpec {
            query += params
            return this
        }

        fun uri(): URI {
            val queryString = query.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}"
            }
            val suffix = if (queryString.isEmpty()) "" else "?$queryString"
            return URI.create("$baseUrl$path$suffix")
        }
    }

    private inline fun <reified T> getJson(spec: RequestSpec): T {
        val request = authorizedRequest(spec.uri()).GET().build()
        return decode(send(request))
    }

    private inline fun <reified B, reified T> postJson(spec: RequestSpec, body: B): T {
        val request = authorizedRequest(spec.uri())
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(json.encodeToString(body)))
            .build()
        return decode(send(request))
    }

    private fun send(request: HttpRequest, retried: Boolean = false): java.net.http.HttpResponse<String> {
        val response = http.send(request, BodyHandlers.ofString())

        if (response.statusCode() == 404) {
            throw LoodsmanApiException("Loodsman resource not found (404): ${request.uri()}")
        }

        if ((response.statusCode() == 401 || response.statusCode() == 419) && !retried) {
            cachedSessionId = null
            return send(request, retried = true)
        }

        if (response.statusCode() == 401 || response.statusCode() == 419) {
            throw LoodsmanApiException("Loodsman session expired or unauthorized (${response.statusCode()}): ${request.uri()}")
        }

        if (response.statusCode() !in 200..299) {
            throw LoodsmanApiException("Loodsman API error ${response.statusCode()} for ${request.uri()}: ${response.body()}")
        }

        return response
    }

    private inline fun <reified T> decode(response: java.net.http.HttpResponse<String>): T =
        try {
            json.decodeFromString(response.body())
        } catch (e: SerializationException) {
            throw LoodsmanApiException("Malformed JSON response from Loodsman: ${e.message}")
        }

    private fun parseQuantity(raw: String, linkId: Int): Int {
        val normalized = raw.trim().replace(',', '.')
        val value = normalized.toDoubleOrNull()
            ?: throw LoodsmanApiException("Cannot parse '$ATTR_QUANTITY' value '$raw' for link $linkId")
        return Math.round(value).toInt()
    }
}

@Serializable
private data class LoginRequest(val dbName: String, val username: String, val password: String, val rememberMe: Boolean = false)

@Serializable
private data class SessionResponse(val sessionId: String? = null)

@Serializable
private data class PropObjectDto(
    val idVersion: Int = 0,
    val type: String? = null,
    val product: String? = null,
    val version: String? = null,
    val state: String? = null
)

@Serializable
private data class LinkedObjectDto(
    val linkId: Int = 0,
    val versionId: Int = 0,
    val linkTypeId: Int = 0,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null
) {
    val idLink: Int get() = linkId
    val idChild: Int get() = versionId
    val idType: Int get() = linkTypeId
}

@Serializable
private data class TypeListEntry(val id: Int, val typeName: String? = null)

@Serializable
private data class LinkListEntry(val id: Int, val name: String? = null)

@Serializable
private data class ObjectAttributeDto(val name: String? = null, val value: String? = null)

@Serializable
private data class LinkAttributeDto(val name: String? = null, val value: String? = null)
