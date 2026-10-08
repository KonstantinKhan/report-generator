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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ASSEMBLY_COMPOSITION_LINK_NAME = "Состоит из ..."
private const val DOCUMENTS_LINK_NAME = "Документы"
private const val ATTR_PRODUCT_DESIGNATION = "Обозначение изделия" // нет атрибута в ответе = не заполнен (сервер не отдаёт пустые)
private const val ATTR_NAME = "Наименование"
private const val ATTR_QUANTITY = "Количество"

class LoodsmanPdmClient(private val config: LoodsmanConfig) : PdmClient {

    private val baseUrl = config.baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    // Dumps raw request/response JSON to stderr — flip on with LOODSMAN_DEBUG=1 to see exactly
    // what this Loodsman instance actually sends (its API surface can differ from the swagger
    // doc — see memory: older instances omit newer fields entirely rather than nulling them).
    private val debug = System.getenv("LOODSMAN_DEBUG") == "1"

    private var cachedSessionId: String? = null
    private var cachedAssemblyLinkTypeId: Int? = null
    private var cachedDocumentsLinkTypeId: Int? = null
    private var metaLoaded = false

    override fun fetchSpecification(documentId: String): SpecificationDto {
        val versionId = documentId.toIntOrNull()
            ?: throw LoodsmanApiException("documentId must be a numeric Loodsman versionId, got '$documentId'")

        ensureMetaLoaded()
        val linkTypeId = cachedAssemblyLinkTypeId!!

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
        // Key attribute (product) is Обозначение for Деталь/СЕ, Наименование for everything else.
        val documentName = if (isDesignationKeyed(prop.type)) {
            docAttrMap[ATTR_NAME]?.value?.trim() ?: documentDesignation
        } else {
            documentDesignation
        }

        // Linked objects: composition ("Состоит из ...") for everything except Документация, and the link
        // "Документы" for Документация (Сборочный чертеж). Each link contributes only its own kinds.
        val compositionChildren = getLinkedObjects(versionId, linkTypeId).map {
            ChildLink(it.idLink, it.idChild, it.idType, it.minQuantity, it.maxQuantity, it.unit)
        }
        val documentChildren = cachedDocumentsLinkTypeId?.let { docLinkId ->
            getLinkedObjects(versionId, docLinkId).map {
                ChildLink(it.idLink, it.idChild, it.idType, it.minQuantity, it.maxQuantity, it.unit)
            }
        }.orEmpty()

        // Get type, key attribute (product) and attributes for all child objects
        val typeNameByObjectId = HashMap<Int, String>()
        val productByObjectId = HashMap<Int, String>()
        val nameByObjectId = HashMap<Int, String>()
        val productDesignationByObjectId = HashMap<Int, String>()

        (compositionChildren + documentChildren).distinctBy { it.idChild }.forEach { child ->
            val childProps = getJson<List<PropObjectDto>>(
                get("/api/v4/ObjectInfo/get-prop-objects").withQuery("objectList" to child.idChild.toString())
            ).firstOrNull()
                ?: throw LoodsmanApiException("Child object ${child.idChild} not found")

            childProps.type?.trim()?.let {
                typeNameByObjectId[child.idChild] = it
            }

            // Unrecognized types are skipped by buildItems: no product required, no attribute requests.
            if (mapItemKind(childProps.type) == null) return@forEach

            productByObjectId[child.idChild] = childProps.product?.trim()
                ?: throw LoodsmanApiException("Child object ${child.idChild} missing product (key attribute)")

            // Наименование is a separate attribute for designation-keyed types (product = Обозначение);
            // "Обозначение изделия" is a separate attribute for Стандартное/Прочее изделие.
            val needsName = isDesignationKeyed(childProps.type)
            val needsProductDesignation = hasProductDesignationAttr(childProps.type)
            if (needsName || needsProductDesignation) {
                val childAttrs = getJson<List<ObjectAttributeDto>>(
                    get("/api/v4/ObjectInfo/get-info-about-version-mode-3").withQuery("idVersion" to child.idChild.toString())
                )
                if (needsName) {
                    childAttrs.find { it.name == ATTR_NAME }?.value?.trim()?.let {
                        nameByObjectId[child.idChild] = it
                    }
                }
                if (needsProductDesignation) {
                    childAttrs.find { it.name?.trim() == ATTR_PRODUCT_DESIGNATION }?.value?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let { productDesignationByObjectId[child.idChild] = it }
                }
            }
        }

        // Quantity from minQuantity/maxQuantity. Kept as Double: MATERIAL items can carry fractional amounts
        // (e.g. 1.5 м); other kinds are whole counts rounded at display time (see the `quantity` cell of the
        // `body` table in gost-spec.yaml). A link without quantity is absent here: buildItems rejects it
        // (except Документация, whose "Кол." is not shown).
        val quantityByLinkId = HashMap<Int, Double>()
        val unitByLinkId = HashMap<Int, String?>()
        (compositionChildren + documentChildren).forEach { child ->
            val quantity = if (child.minQuantity != null && child.minQuantity == child.maxQuantity) {
                child.minQuantity
            } else {
                child.minQuantity ?: child.maxQuantity
            }
            if (quantity != null) quantityByLinkId[child.idLink] = quantity
            unitByLinkId[child.idLink] = child.unit
        }

        fun List<ChildLink>.ofKinds(documentation: Boolean) = filter {
            (mapItemKind(typeNameByObjectId[it.idChild]) == "DOCUMENTATION") == documentation
        }
        val items = buildItems(
            compositionChildren.ofKinds(documentation = false), typeNameByObjectId, productByObjectId, nameByObjectId,
            quantityByLinkId, unitByLinkId, productDesignationByObjectId,
        ) + buildItems(
            documentChildren.ofKinds(documentation = true), typeNameByObjectId, productByObjectId, nameByObjectId,
            quantityByLinkId, unitByLinkId, productDesignationByObjectId,
        )

        return SpecificationDto(documentDesignation, documentName, items)
    }

    @Synchronized
    private fun ensureMetaLoaded() {
        if (metaLoaded) return

        val linkTypes = getJson<List<LinkListEntry>>(get("/api/v4/MetaData/get-link-list"))
        cachedAssemblyLinkTypeId = linkTypes.firstOrNull { it.name?.trim() == ASSEMBLY_COMPOSITION_LINK_NAME }?.id
            ?: throw LoodsmanApiException("Link type '$ASSEMBLY_COMPOSITION_LINK_NAME' not found in Loodsman metadata")
        // Optional: an instance without the link simply yields no Документация group.
        cachedDocumentsLinkTypeId = linkTypes.firstOrNull { it.name?.trim() == DOCUMENTS_LINK_NAME }?.id
        metaLoaded = true
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

        if (debug) {
            System.err.println("[loodsman] ${request.method()} ${request.uri()} -> ${response.statusCode()}")
            System.err.println(response.body())
        }

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
    @SerialName("idLink")
    val linkId: Int = 0,
    @SerialName("idChild")
    val versionId: Int = 0,
    @SerialName("idType")
    val linkTypeId: Int = 0,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val unit: String? = null
) {
    val idLink: Int get() = linkId
    val idChild: Int get() = versionId
    val idType: Int get() = linkTypeId
}

@Serializable
private data class LinkListEntry(val id: Int, val name: String? = null)

@Serializable
private data class ObjectAttributeDto(val name: String? = null, val value: String? = null)

@Serializable
private data class LinkAttributeDto(val name: String? = null, val value: String? = null)
