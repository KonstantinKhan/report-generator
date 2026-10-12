package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.PdmClient
import dev.reportgenerator.domain.PdmDatabase
import dev.reportgenerator.domain.SpecificationData
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

private const val ASSEMBLY_COMPOSITION_LINK_NAME = "Состоит из ..."
private const val DOCUMENTS_LINK_NAME = "Документы"
private const val ATTR_PRODUCT_DESIGNATION = "Обозначение изделия"
private const val ATTR_NAME = "Наименование"
private const val ATTR_QUANTITY = "Количество"

class LoodsmanKtorPdmClient(
    private val config: LoodsmanConfig,
    private val httpClient: HttpClient = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpCookies)
        install(Logging) {
            level = LogLevel.INFO
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, request ->
                if (cause is CancellationException || cause is LoodsmanApiException) throw cause
                if (cause is ResponseException) {
                    throw LoodsmanApiException(
                        "Loodsman API error ${cause.response.status.value} for " +
                            "${request.method.value} ${request.url}: ${cause.response.bodyAsText().take(500)}",
                        cause,
                        cause.response.status.value
                    )
                }
                val reason = when (cause) {
                    is UnresolvedAddressException -> "host not resolved: ${request.url.host}"
                    is ConnectException -> "connection refused: ${request.url.host}:${request.url.port}"
                    else -> cause.message ?: cause::class.simpleName
                }
                throw LoodsmanApiException(
                    "Loodsman request failed: ${request.method.value} ${request.url}: $reason",
                    cause
                )
            }
        }
    }
) : PdmClient {

    private val baseUrl = config.baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = ReentrantReadWriteLock()
    private var cachedAssemblyLinkTypeId: Int? = null
    private var cachedDocumentsLinkTypeId: Int? = null
    private var metaLoaded = false

    override fun fetchSpecification(documentId: String): SpecificationData = runBlocking {
        val versionId = documentId.toIntOrNull()
            ?: throw LoodsmanApiException("documentId must be a numeric Loodsman versionId, got '$documentId'")

        ensureMetaLoaded()
        val linkTypeId = cachedAssemblyLinkTypeId!!

        // Get prop objects (type, product, version, state)
        val propObjects = getJson<List<PropObjectDto>>(
            "$baseUrl/api/v4/ObjectInfo/get-prop-objects?objectList=${encodeQueryParam(versionId.toString())}"
        )
        val prop = propObjects.firstOrNull()
            ?: throw LoodsmanApiException("Document not found in Loodsman: versionId=$versionId")

        // Get document attributes (name, designation)
        val docAttrs = getJson<List<ObjectAttributeDto>>(
            "$baseUrl/api/v4/ObjectInfo/get-info-about-version-mode-3?idVersion=${encodeQueryParam(versionId.toString())}"
        )
        val docAttrMap = docAttrs.associateBy { it.name }

        val documentDesignation = prop.product?.trim()
            ?: throw LoodsmanApiException("Document not found in Loodsman: no product (designation) for versionId=$versionId")

        val documentName = if (isDesignationKeyed(prop.type)) {
            docAttrMap[ATTR_NAME]?.value?.trim() ?: documentDesignation
        } else {
            documentDesignation
        }

        // Linked objects: composition for everything, and documents for Документация
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
                "$baseUrl/api/v4/ObjectInfo/get-prop-objects?objectList=${encodeQueryParam(child.idChild.toString())}"
            ).firstOrNull()
                ?: throw LoodsmanApiException("Child object ${child.idChild} not found")

            childProps.type?.trim()?.let {
                typeNameByObjectId[child.idChild] = it
            }

            if (mapItemKind(childProps.type) == null) return@forEach

            productByObjectId[child.idChild] = childProps.product?.trim()
                ?: throw LoodsmanApiException("Child object ${child.idChild} missing product (key attribute)")

            val needsName = isDesignationKeyed(childProps.type)
            val needsProductDesignation = hasProductDesignationAttr(childProps.type)
            if (needsName || needsProductDesignation) {
                val childAttrs = getJson<List<ObjectAttributeDto>>(
                    "$baseUrl/api/v4/ObjectInfo/get-info-about-version-mode-3?idVersion=${encodeQueryParam(child.idChild.toString())}"
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
            (mapItemKind(typeNameByObjectId[it.idChild]) == ItemKind.DOCUMENTATION) == documentation
        }

        val items = buildItems(
            compositionChildren.ofKinds(documentation = false), typeNameByObjectId, productByObjectId, nameByObjectId,
            quantityByLinkId, unitByLinkId, productDesignationByObjectId,
        ) + buildItems(
            documentChildren.ofKinds(documentation = true), typeNameByObjectId, productByObjectId, nameByObjectId,
            quantityByLinkId, unitByLinkId, productDesignationByObjectId,
        )

        SpecificationData(documentDesignation, documentName, items)
    }

    suspend fun pdmDatabases(): List<PdmDatabase> = auth.databases().map { db -> db.toDomain() }

    suspend fun userId(dbName: String, username: String, password: String) = auth.login(dbName, username, password).userId

    suspend fun isAdmin(): Boolean = sessions.withSession { auth.isAdmin(it) }

    suspend fun logout() = sessions.close()

    private val auth: Auth = Auth(httpClient, baseUrl)
    private val sessions = SessionHolder(auth, config)

    private fun ensureMetaLoaded() {
        lock.write {
            if (metaLoaded) return@write

            runBlocking {
                val linkTypes = getJson<List<LinkListEntry>>(
                    "$baseUrl/api/v4/MetaData/get-link-list"
                )
                cachedAssemblyLinkTypeId =
                    linkTypes.firstOrNull { it.name?.trim() == ASSEMBLY_COMPOSITION_LINK_NAME }?.id
                        ?: throw LoodsmanApiException("Link type '$ASSEMBLY_COMPOSITION_LINK_NAME' not found in Loodsman metadata")
                cachedDocumentsLinkTypeId = linkTypes.firstOrNull { it.name?.trim() == DOCUMENTS_LINK_NAME }?.id
                metaLoaded = true
            }
        }
    }

    private suspend fun getLinkedObjects(objectId: Int, linkTypeId: Int): List<LinkedObjectDto> =
        getJson(
            buildUrl(
                "/api/v4/ObjectInfo/get-linked-objects-for-objects",
                "objectsIds" to objectId.toString(),
                "linksTypesIds" to linkTypeId.toString(),
                "inverse" to "false"
            )
        )

    private suspend inline fun <reified T> getJson(url: String): T {
        val response = sessions.withSession { sessionId ->
            httpClient.get(url) {
                header("web-loodsman-session", sessionId)
                header("x-loodsman-db-name", config.dbName)
            }
        }
        return decode<T>(response)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T =
        try {
            json.decodeFromString(response.bodyAsText())
        } catch (e: SerializationException) {
            throw LoodsmanApiException("Malformed JSON response from Loodsman: ${e.message}")
        }

    private fun buildUrl(path: String, vararg params: Pair<String, String>): String {
        val queryString = params.joinToString("&") { (k, v) ->
            "${encodeQueryParam(k)}=${encodeQueryParam(v)}"
        }
        val suffix = if (queryString.isEmpty()) "" else "?$queryString"
        return "$baseUrl$path$suffix"
    }

    private fun encodeQueryParam(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
