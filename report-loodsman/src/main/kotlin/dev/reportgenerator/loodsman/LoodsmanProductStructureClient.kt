package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.ProductNode
import dev.reportgenerator.domain.ProductStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.UnmappedUsage
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import dev.reportgenerator.loodsman.dto.PropObjectOutputDto
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.FieldSource
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.LinkField
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.PropField
import dev.reportgenerator.loodsman.mapping.Registries
import dev.reportgenerator.loodsman.mapping.RelationMapper
import dev.reportgenerator.loodsman.mapping.nameFor
import dev.reportgenerator.loodsman.mapping.select
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
        val attributeRules = resolveAttributeRules(query)
        validateLinkAttributes(query)

        val nodes = HashMap<Int, LoadedNode>()
        val links = HashMap<Int, List<Link>>()
        var frontier = listOf(rootId)
        var level = 0
        while (frontier.isNotEmpty()) {
            loadNodes(frontier, query, attributeRules, nodes)
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

        val linkAttributes = loadLinkAttributes(query, nodes, links)
        return buildNode(rootId, query.depth, query, attributeRules, nodes, links, linkAttributes, emptyList())
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

    private fun validateLinkAttributes(query: StructureQuery) {
        for (key in query.linkAttributes) {
            if (key == LinkAttributeKey.Position) {
                throw LoodsmanApiException("Link attribute 'position' is built in: read Usage.position instead")
            }
            val id = Registries.linkAttributeKeys.idOf(key)
                ?: throw LoodsmanApiException("Link attribute key '$key' not found in Registries")
            if (id !in mapping.link.attributes) {
                throw LoodsmanApiException("Link attribute '$id' is not configured in mapping.link.attributes")
            }
        }
    }

    private fun resolveAttributeRules(query: StructureQuery): Map<AttributeKey, AttributeRule> =
        query.attributes.associateWith { key ->
            Registries.attributeKeys.idOf(key)?.let { mapping.attributes[it] }
                ?: throw LoodsmanApiException("Attribute '$key' is not configured in mapping.attributes")
        }

    private suspend fun loadNodes(
        ids: List<Int>,
        query: StructureQuery,
        attributeRules: Map<AttributeKey, AttributeRule>,
        nodes: MutableMap<Int, LoadedNode>
    ) {
        val props = ids.chunked(PROPS_BATCH_SIZE).flatMap { chunk ->
            sessions.withSession { objectInfo.pdmObjects(it, chunk) }
        }.associateBy { it.idVersion }

        val prepared = ids.map { id ->
            val prop = props[id] ?: throw LoodsmanApiException("Object not found in Loodsman: id=$id")
            id to Pair(prop, kindMapper.map(prop.type))
        }

        val attributes = withPermits(prepared.filter { (_, pair) ->
            val kind = pair.second
            kind != null && needsAttributes(kind, query, attributeRules)
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
        query: StructureQuery,
        attributeRules: Map<AttributeKey, AttributeRule>
    ): Boolean {
        val identity = identityOf(kind)
        return identity.designation is FieldSource.Attribute ||
            identity.name is FieldSource.Attribute ||
            attributeRules.values.any { it.nameFor(kind) != null } ||
            query.includeUnmappedAttributes
    }

    private suspend fun loadLinkAttributes(
        query: StructureQuery,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>
    ): Map<Int, LinkAttributesData> {
        if (query.linkAttributes.isEmpty() && mapping.link.positionRules.isEmpty()) {
            return emptyMap()
        }

        val allLinks = links.values.flatten()
        val linksByLinkId = mutableMapOf<Int, Link>()
        for (link in allLinks) {
            val parentNode = nodes[link.dto.idParent]
            val childNode = nodes[link.dto.idChild]
            if (parentNode?.kind != null && childNode?.kind != null) {
                linksByLinkId[link.linkId] = link
            }
        }

        if (linksByLinkId.isEmpty()) {
            return emptyMap()
        }

        val requestedRules = query.linkAttributes.map { key ->
            mapping.link.attributes.getValue(Registries.linkAttributeKeys.idOf(key)!!)
        }

        val result = mutableMapOf<Int, LinkAttributesData>()
        val attributesByLinkId = withPermits(linksByLinkId.keys.toList()) { linkId ->
            val link = linksByLinkId[linkId]!!
            val parentKind = nodes[link.dto.idParent]!!.kind!!
            val childKind = nodes[link.dto.idChild]!!.kind!!
            val needsAttributeRequest =
                mapping.link.positionRules.select(link.relation, parentKind, childKind) != null ||
                    requestedRules.any { it.select(link.relation, parentKind, childKind) != null }

            if (!needsAttributeRequest) {
                return@withPermits mapOf<String, String>()
            }

            sessions.withSession { objectInfo.linkAttributes(it, linkId) }
                .associate { it.name?.trim().orEmpty() to (it.value?.trim().orEmpty()) }
                .filterKeys { it.isNotEmpty() }
        }

        for ((linkId, attrs) in attributesByLinkId) {
            result[linkId] = LinkAttributesData(linkId, attrs)
        }

        return result
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
        attributeRules: Map<AttributeKey, AttributeRule>,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>,
        linkAttributes: Map<Int, LinkAttributesData>,
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
                val childKind = child.kind
                val linkAttrs = linkAttributes[link.linkId]?.attributes ?: emptyMap()
                val attrs = linkAttributesOf(link.relation, kind, childKind, query, linkAttrs)
                val childNode = buildNode(
                    link.dto.idChild, remaining - 1, query, attributeRules, nodes, links, linkAttributes, path + id
                )

                when (link.relation) {
                    Relation.Composition -> {
                        val positionRule = mapping.link.positionRules.select(link.relation, kind, childKind)
                        val position = positionRule?.name
                            ?.let { posName -> linkAttrs[posName]?.toIntOrNull() }
                            ?.let { Field.Value(it) } ?: Field.Missing
                        CompositionUsage(
                            child = childNode,
                            position = position,
                            quantity = quantityOf(link.dto),
                            unit = link.dto.text(mapping.link.fields.unit)?.let { Field.Value(it) } ?: Field.Missing,
                            attributes = attrs
                        )
                    }
                    Relation.Document -> DocumentUsage(child = childNode, attributes = attrs)
                    is Relation.Unmapped -> UnmappedUsage(relation = link.relation, child = childNode, attributes = attrs)
                }
            }
        } else {
            emptyList()
        }

        return ProductNode(
            id = ProductId(id.toString()),
            kind = kind,
            designation = node.read(identity.designation)?.let { Designation.Value(it) } ?: Designation.Missing,
            name = node.read(identity.name)?.let { Name.Value(it) } ?: Name.Missing,
            version = node.read(FieldSource.Prop(mapping.properties.version))?.let { Field.Value(it) } ?: Field.Missing,
            state = node.read(FieldSource.Prop(mapping.properties.state))?.let { Field.Value(it) } ?: Field.Missing,
            attributes = attributesOf(node, kind, query, attributeRules),
            usages = usages
        )
    }

    private fun attributesOf(
        node: LoadedNode,
        kind: ProductKind,
        query: StructureQuery,
        rules: Map<AttributeKey, AttributeRule>
    ): Map<AttributeKey, AttributeValue> {
        val result = LinkedHashMap<AttributeKey, AttributeValue>()
        rules.forEach { (key, rule) ->
            val value = rule.nameFor(kind)?.let { name -> node.read(FieldSource.Attribute(name)) }
            result[key] = value?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
        }
        if (query.includeUnmappedAttributes) {
            val mappedNames = mutableSetOf<String>()
            mapping.attributes.values.forEach { rule ->
                rule.name?.let { mappedNames.add(it) }
                rule.byKind.values.forEach { mappedNames.add(it) }
            }
            mapping.identity.designation.let { ds ->
                when (ds) {
                    is FieldSource.Attribute -> mappedNames.add(ds.name)
                    else -> {}
                }
            }
            mapping.identity.name.let { n ->
                when (n) {
                    is FieldSource.Attribute -> mappedNames.add(n.name)
                    else -> {}
                }
            }
            mapping.identity.byKind.values.forEach { ki ->
                ki.designation?.let { ds ->
                    when (ds) {
                        is FieldSource.Attribute -> mappedNames.add(ds.name)
                        else -> {}
                    }
                }
                ki.name?.let { n ->
                    when (n) {
                        is FieldSource.Attribute -> mappedNames.add(n.name)
                        else -> {}
                    }
                }
            }
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

    private fun linkAttributesOf(
        relation: Relation,
        parentKind: ProductKind,
        childKind: ProductKind,
        query: StructureQuery,
        linkAttrs: Map<String, String>
    ): Map<LinkAttributeKey, AttributeValue> {
        val result = LinkedHashMap<LinkAttributeKey, AttributeValue>()
        for (key in query.linkAttributes) {
            val id = Registries.linkAttributeKeys.idOf(key) ?: continue
            val rules = mapping.link.attributes[id] ?: continue
            val rule = rules.select(relation, parentKind, childKind)
            val value = rule?.name?.let { name -> linkAttrs[name] }?.takeIf { it.isNotEmpty() }
            result[key] = value?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
        }
        return result
    }

    private fun quantityOf(dto: GetLinkedObjectsForObjectsOutputDto_V4): Quantity {
        val min = dto.number(mapping.link.fields.quantityMin)
        val max = dto.number(mapping.link.fields.quantityMax)
        return when {
            min == null && max == null -> Quantity.Missing
            min == null -> Quantity.Exact(max!!)
            max == null || min == max -> Quantity.Exact(min)
            else -> Quantity.Range(min, max)
        }
    }

    private fun GetLinkedObjectsForObjectsOutputDto_V4.number(field: LinkField): Double? = when (field) {
        LinkField.MIN_QUANTITY -> minQuantity
        LinkField.MAX_QUANTITY -> maxQuantity
        LinkField.UNIT -> null
    }

    private fun GetLinkedObjectsForObjectsOutputDto_V4.text(field: LinkField): String? = when (field) {
        LinkField.UNIT -> unit?.trim()?.takeIf { it.isNotEmpty() }
        LinkField.MIN_QUANTITY, LinkField.MAX_QUANTITY -> null
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

    private class Link(
        val relation: Relation,
        val dto: GetLinkedObjectsForObjectsOutputDto_V4,
        val linkId: Int = dto.idLink
    )

    private data class LinkAttributesData(
        val linkId: Int,
        val attributes: Map<String, String>
    )

    private class Identity(val designation: FieldSource, val name: FieldSource)
}
