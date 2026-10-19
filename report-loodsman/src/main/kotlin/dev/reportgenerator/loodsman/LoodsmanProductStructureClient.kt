package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.ProductNode
import dev.reportgenerator.domain.ProductStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import dev.reportgenerator.loodsman.dto.PropObjectOutputDto
import dev.reportgenerator.loodsman.mapping.FieldSource
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.PropField
import dev.reportgenerator.loodsman.mapping.Registries
import dev.reportgenerator.loodsman.mapping.RelationMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

private const val PROPS_BATCH_SIZE = 100

fun defaultLoodsmanHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpCookies)
    install(HttpTimeout)
    install(Logging) {
        level = LogLevel.INFO
    }
    HttpResponseValidator {
        handleResponseExceptionWithRequest { cause, request ->
            if (cause is CancellationException || cause is LoodsmanApiException) throw cause
            if (cause is ResponseException) {
                throw LoodsmanApiException(
                    "Loodsman API error ${cause.response.status.value} for " +
                            "${request.method.value} ${request.url}",
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

class LoodsmanProductStructureClient(
    config: LoodsmanConfig,
    private val mapping: MappingConfig = MappingConfigLoader.load(),
    httpClient: HttpClient = defaultLoodsmanHttpClient(),
    private val concurrency: Int = 8
) : ProductStructureClient {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val baseUrl = config.baseUrl.trimEnd('/')

    private val auth = Auth(httpClient, baseUrl)
    private val objectInfo = ObjectInfo(httpClient, baseUrl)
    private val metaData = MetaData(httpClient, baseUrl)
    private val sessions = SessionHolder(auth, config)

    private val linkMetadata = SuspendLazy { sessions.withSession { metaData.linkList(it) } }
    private val kindMapper = KindMapper(mapping, log)
    private val relationMapper = RelationMapper(mapping, log)

    suspend fun logout() = sessions.close()

    override suspend fun loadStructure(query: StructureQuery): ProductNode {
        val rootId = query.rootId.value.toIntOrNull()
            ?: throw LoodsmanApiException("rootId must be numeric, got '${query.rootId.value}'")
        val relationByLinkTypeId = resolveLinkTypes(query.relations)
        val attributeSources = resolveAttributeSources(query)

        val nodes = HashMap<Int, LoadedNode>()
        val links = HashMap<Int, List<Link>>()
        var frontier = listOf(rootId)
        var level = 0
        while (frontier.isNotEmpty()) {
            loadNodes(frontier, rootId, query, attributeSources.isNotEmpty(), nodes)
            if (level >= query.depth) break

            val expandable = frontier.filter { nodes.getValue(it).kind != null }
            links.putAll(loadLinks(expandable, relationByLinkTypeId))
            frontier = expandable.flatMap { id -> links.getValue(id).map { it.dto.idChild } }
                .distinct()
                .filter { it !in nodes }
            level++
        }

        val root = nodes.getValue(rootId)
        if (root.kind == null) {
            throw LoodsmanApiException("Root object $rootId has unmapped type '${root.prop.type}'")
        }
        return buildNode(rootId, query.depth, query, attributeSources, nodes, links, emptyList())
    }

    private suspend fun resolveLinkTypes(relations: Set<Relation>): Map<Int, Relation> {
        val linkList = linkMetadata.get().filter { it.type == 0 }

        fun linkTypeId(pdmName: String): Int? =
            linkList.firstOrNull { it.name.normalized() == pdmName.normalized() }?.id

        for (requiredId in mapping.requiredRelations) {
            val pdmNames = mapping.relations.filterValues { it == requiredId }.keys
            if (pdmNames.none { linkTypeId(it) != null }) {
                throw LoodsmanApiException("Link type '${pdmNames.joinToString()}' not found in Loodsman metadata")
            }
        }

        return relations.associateBy { relation ->
            val pdmName = relationMapper.pdmNameOf(relation)
                ?: throw LoodsmanApiException("Relation $relation is not configured in mapping")
            linkTypeId(pdmName)
                ?: throw LoodsmanApiException("Link type '$pdmName' not found in Loodsman metadata")
        }
    }

    private fun resolveAttributeSources(query: StructureQuery): Map<AttributeKey, FieldSource> =
        query.attributes.associateWith { key ->
            val id = Registries.attributeKeys.idOf(key)
            id?.let { mapping.attributes[it] }
                ?: throw LoodsmanApiException("Attribute '$key' is not configured in mapping.attributes")
        }

    private suspend fun loadNodes(
        ids: List<Int>,
        rootId: Int,
        query: StructureQuery,
        hasRequestedAttributes: Boolean,
        nodes: MutableMap<Int, LoadedNode>
    ) {
        val props = ids.chunked(PROPS_BATCH_SIZE).flatMap { chunk ->
            sessions.withSession { objectInfo.pdmObjects(it, chunk) }
        }.associateBy { it.idVersion }

        val prepared = ids.map { id ->
            val prop = props[id] ?: throw LoodsmanApiException("Object not found in Loodsman: id=$id")
            id to Pair(prop, kindMapper.map(prop.type))
        }

        val attributes = withPermits(prepared.filter { (id, pair) ->
            val (_, kind) = pair
            kind != null && needsAttributes(kind, id == rootId, query, hasRequestedAttributes)
        }.map { it.first }) { id -> sessions.withSession { objectInfo.infoAboutVersion(it, id) } }

        prepared.forEach { (id, pair) ->
            nodes[id] = LoadedNode(pair.first, pair.second, attributes[id].orEmpty())
        }
    }

    private suspend fun loadLinks(ids: List<Int>, relationByLinkTypeId: Map<Int, Relation>): Map<Int, List<Link>> {
        if (relationByLinkTypeId.isEmpty()) return ids.associateWith { emptyList() }
        val linkTypeIds = relationByLinkTypeId.keys.toList()
        return withPermits(ids) { id ->
            sessions.withSession { objectInfo.linkedObjects(it, id, linkTypeIds, inverse = false) }
                .mapNotNull { dto -> relationByLinkTypeId[dto.idLinkType]?.let { Link(it, dto) } }
        }
    }

    private suspend fun <T> withPermits(ids: List<Int>, block: suspend (Int) -> T): Map<Int, T> =
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            ids.map { id -> async { id to semaphore.withPermit { block(id) } } }.awaitAll().toMap()
        }

    private fun needsAttributes(
        kind: ProductKind,
        isRoot: Boolean,
        query: StructureQuery,
        hasRequestedAttributes: Boolean
    ): Boolean {
        val identity = identityOf(kind)
        return identity.designation is FieldSource.Attribute ||
            identity.name is FieldSource.Attribute ||
            (!isRoot && mapping.position is FieldSource.Attribute) ||
            (hasRequestedAttributes && query.attributes.any {
                mapping.attributes[Registries.attributeKeys.idOf(it)] is FieldSource.Attribute
            }) ||
            query.includeUnmappedAttributes
    }

    private fun identityOf(kind: ProductKind): Identity {
        val override = Registries.itemKinds.idOf(kind)?.let { mapping.identity.byKind[it] }
        return Identity(
            designation = override?.designation ?: mapping.identity.designation,
            name = override?.name ?: mapping.identity.name
        )
    }

    private fun buildNode(
        id: Int,
        remaining: Int,
        query: StructureQuery,
        attributeSources: Map<AttributeKey, FieldSource>,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>,
        path: List<Int>
    ): ProductNode {
        if (id in path) {
            throw LoodsmanApiException("Cycle detected in structure: ${(path + id).joinToString(" -> ")}")
        }
        val node = nodes.getValue(id)
        val kind = node.kind ?: throw LoodsmanApiException("Object $id has unmapped type '${node.prop.type}'")
        val identity = identityOf(kind)

        val usages = if (remaining > 0) {
            links[id].orEmpty().mapNotNull { link ->
                val child = nodes.getValue(link.dto.idChild)
                if (child.kind == null) return@mapNotNull null
                Usage(
                    relation = link.relation,
                    child = buildNode(
                        link.dto.idChild, remaining - 1, query, attributeSources, nodes, links, path + id
                    ),
                    position = child.read(mapping.position)?.toIntOrNull()?.let { Field.Value(it) } ?: Field.Missing,
                    quantity = quantityOf(link.dto),
                    unit = link.dto.unit?.trim()?.takeIf { it.isNotEmpty() }?.let { Field.Value(it) } ?: Field.Missing
                )
            }
        } else {
            emptyList()
        }

        return ProductNode(
            id = ProductId(id.toString()),
            kind = kind,
            designation = node.read(identity.designation)?.let { Designation.Value(it) } ?: Designation.Missing,
            name = node.read(identity.name)?.let { Name.Value(it) } ?: Name.Missing,
            attributes = attributesOf(node, query, attributeSources),
            usages = usages
        )
    }

    private fun attributesOf(
        node: LoadedNode,
        query: StructureQuery,
        sources: Map<AttributeKey, FieldSource>
    ): Map<AttributeKey, AttributeValue> {
        val result = LinkedHashMap<AttributeKey, AttributeValue>()
        sources.forEach { (key, source) ->
            result[key] = node.read(source)?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
        }
        if (query.includeUnmappedAttributes) {
            val mappedNames = mapping.attributes.values.filterIsInstance<FieldSource.Attribute>().map { it.name }.toSet()
            node.attributes.forEach { attribute ->
                val name = attribute.name?.trim()
                val value = attribute.value?.trim()
                if (!name.isNullOrEmpty() && name !in mappedNames && !value.isNullOrEmpty()) {
                    result[AttributeKey.Unmapped(name)] = AttributeValue.Text(value)
                }
            }
        }
        return result
    }

    private fun quantityOf(dto: GetLinkedObjectsForObjectsOutputDto_V4): Quantity {
        val min = dto.minQuantity
        val max = dto.maxQuantity
        return when {
            min == null && max == null -> Quantity.Missing
            min == null -> Quantity.Exact(max!!)
            max == null || min == max -> Quantity.Exact(min)
            else -> Quantity.Range(min, max)
        }
    }

    private fun LoadedNode.read(source: FieldSource): String? = when (source) {
        is FieldSource.Prop -> when (source.field) {
            PropField.PRODUCT -> prop.product
            PropField.VERSION -> prop.version
            PropField.STATE -> prop.state
            PropField.TYPE -> prop.type
        }
        is FieldSource.Attribute -> attributes.firstOrNull { it.name?.trim() == source.name }?.value
    }?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.normalized(): String =
        this.orEmpty().lowercase().replace("\\s+".toRegex(), " ").trim()

    private class LoadedNode(
        val prop: PropObjectOutputDto,
        val kind: ProductKind?,
        val attributes: List<ObjectAttributeOutputDto>
    )

    private class Link(val relation: Relation, val dto: GetLinkedObjectsForObjectsOutputDto_V4)

    private class Identity(val designation: FieldSource, val name: FieldSource)
}
