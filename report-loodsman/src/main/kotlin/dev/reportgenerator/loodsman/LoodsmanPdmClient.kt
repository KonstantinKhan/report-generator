package dev.reportgenerator.loodsman

import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.api.SpecificationDto
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

private const val ASSEMBLY_COMPOSITION_LINK_NAME = "Состоит из"
private const val ATTR_DESIGNATION = "Обозначение"
private const val ATTR_NAME = "Наименование"
private const val ATTR_QUANTITY = "Количество"

class LoodsmanPdmClient(private val config: LoodsmanConfig) : PdmClient {

    private val baseUrl = config.baseUrl.trimEnd('/')
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedSessionId: String? = null
    private var cachedAssemblyLinkTypeId: Int? = null
    private var cachedTypeNameById: Map<Int, String>? = null

    override fun fetchSpecification(documentId: String): SpecificationDto {
        val objectId = documentId.toIntOrNull()
            ?: throw LoodsmanApiException("documentId must be a numeric Loodsman versionId, got '$documentId'")

        ensureMetaLoaded()
        val linkTypeId = cachedAssemblyLinkTypeId!!
        val typeNameById = cachedTypeNameById!!

        val children = getLinkedObjects(objectId, linkTypeId).map { ChildLink(it.idLink, it.idChild, it.idType) }

        val objectIds = (children.map { it.idChild } + objectId).distinct()
        val designationByObjectId = HashMap<Int, String>()
        val nameByObjectId = HashMap<Int, String>()
        if (objectIds.isNotEmpty()) {
            fetchObjectAttributes(objectIds, listOf(ATTR_DESIGNATION, ATTR_NAME)).forEach { result ->
                val value = result.attributeInfo?.textPlainValue
                if (result.isSuccess && value != null) {
                    when (result.attributeInfo.name) {
                        ATTR_DESIGNATION -> designationByObjectId[result.objectId] = value
                        ATTR_NAME -> nameByObjectId[result.objectId] = value
                    }
                }
            }
        }

        val linkIds = children.map { it.idLink }.distinct()
        val quantityByLinkId = HashMap<Int, Int>()
        if (linkIds.isNotEmpty()) {
            fetchLinkAttributes(linkIds, listOf(ATTR_QUANTITY)).forEach { result ->
                val value = result.attributeInfo?.textPlainValue
                if (result.isSuccess && value != null && result.attributeInfo.name == ATTR_QUANTITY) {
                    quantityByLinkId[result.linkId] = parseQuantity(value, result.linkId)
                }
            }
        }

        val documentDesignation = designationByObjectId[objectId]
            ?: throw LoodsmanApiException("Document not found in Loodsman: no '$ATTR_DESIGNATION' for versionId=$objectId")
        val documentName = nameByObjectId[objectId]
            ?: throw LoodsmanApiException("Document not found in Loodsman: no '$ATTR_NAME' for versionId=$objectId")

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

    private fun fetchObjectAttributes(objectIds: List<Int>, attributeNames: List<String>): List<ObjectAttributeResult> =
        postJson(
            post("/api/v4/ObjectInfo/objects/by-ids/attributes/by-names/text-plain-values"),
            objectIds.map { ObjectAttributeRequest(it, attributeNames) },
        )

    private fun fetchLinkAttributes(linkIds: List<Int>, attributeNames: List<String>): List<LinkAttributeResult> =
        postJson(
            post("/api/v4/ObjectInfo/links/by-ids/attributes/by-names/text-plain-values"),
            linkIds.map { LinkAttributeRequest(it, attributeNames) },
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

    // securitySchemes.Bearer is declared as an OpenAPI apiKey header named Authorization (not an
    // "http bearer" scheme) — confirmed by a 401 on every non-login call when a "Bearer " prefix
    // was sent. The raw sessionId is the header value, no prefix.
    private fun authorizedRequest(uri: URI): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(uri)
            .header("Authorization", session())
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

    private fun send(request: HttpRequest) = http.send(request, BodyHandlers.ofString()).also { response ->
        if (response.statusCode() == 404) {
            throw LoodsmanApiException("Loodsman resource not found (404): ${request.uri()}")
        }
        if (response.statusCode() == 401 || response.statusCode() == 419) {
            throw LoodsmanApiException("Loodsman session expired or unauthorized (${response.statusCode()}): ${request.uri()}")
        }
        if (response.statusCode() !in 200..299) {
            throw LoodsmanApiException("Loodsman API error ${response.statusCode()} for ${request.uri()}: ${response.body()}")
        }
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
private data class LinkedObjectDto(val idLink: Int, val idChild: Int, val idType: Int)

@Serializable
private data class TypeListEntry(val id: Int, val typeName: String? = null)

@Serializable
private data class LinkListEntry(val id: Int, val name: String? = null)

@Serializable
private data class AttributeTextPlainValue(val name: String? = null, val textPlainValue: String? = null)

@Serializable
private data class ObjectAttributeResult(
    val objectId: Int,
    val isSuccess: Boolean = true,
    val attributeInfo: AttributeTextPlainValue? = null,
)

@Serializable
private data class LinkAttributeResult(
    val linkId: Int,
    val isSuccess: Boolean = true,
    val attributeInfo: AttributeTextPlainValue? = null,
)

@Serializable
private data class ObjectAttributeRequest(val objectId: Int, val attributesNames: List<String>)

@Serializable
private data class LinkAttributeRequest(val linkId: Int, val attributesNames: List<String>)
