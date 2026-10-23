package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.UnmappedUsage
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.LinkAttributeRule
import dev.reportgenerator.loodsman.mapping.LinkField
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.Registries
import dev.reportgenerator.loodsman.mapping.RelationMapper
import dev.reportgenerator.loodsman.mapping.nameFor
import dev.reportgenerator.loodsman.mapping.select
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import khan366kos.mapping.AttributeRequest
import khan366kos.mapping.toDomain

private const val PROPS_BATCH_SIZE = 100

class LoodsmanProductStructureClient internal constructor(
    private val api: LoodsmanApi,
    private val mapping: MappingConfig,
    private val concurrency: Int = 8,
    private val metadata: MetadataSource
) : PdmNodeStructureClient {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val kindMapper = KindMapper(mapping, log)
    private val relationMapper = RelationMapper(mapping, log)

    override suspend fun loadTree(query: StructureQuery): PdmNode {
        val rootId = query.rootId.asInt()
        val snapshot = metadata.snapshot()
        val relationByLinkTypeId = resolveLinkTypes(snapshot, query.relations)
        query.attributes.forEach { ruleOf(it) }
        validateLinkAttributes(query)

        val nodes = HashMap<Int, LoadedNode>()
        val links = HashMap<Int, List<Link>>()
        var frontier = listOf(rootId)
        var level = 0
        while (frontier.isNotEmpty()) {
            loadNodes(frontier, snapshot, nodes, query)
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
        return buildNode(rootId, query.depth, query, nodes, links, linkAttributes, emptyList())
    }

    override suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean
    ): PdmNode {
        val snapshot = metadata.snapshot()
        val props = api.objectInfo.propObject(id.asInt())
        val kind = kindMapper.map(props.type)
            ?: error("Loodsman object $id: unmapped type '${props.type}'")
        val request = attributeRequest(kind, keyAttributeOf(snapshot, id.asInt(), props), attributeKeys, includeUnmapped)
        return toPdmNode(props, kind, objectAttributesOf(id.asInt(), request), request)
    }

    private fun resolveLinkTypes(snapshot: MetadataSnapshot, relations: Set<Relation>): Map<Int, Relation> =
        relations.associateBy { relation ->
            val pdmName = relationMapper.pdmNameOf(relation)
                ?: throw LoodsmanApiException("Relation $relation is not configured in mapping")
            snapshot.linkTypeId(pdmName)
                ?: throw LoodsmanApiException("Link type '$pdmName' not found in Loodsman metadata")
        }

    private fun validateLinkAttributes(query: StructureQuery) {
        for (key in query.linkAttributes) {
            if (key == LinkAttributeKey.Position) {
                throw LoodsmanApiException("Link attribute 'position' is built in: read Usage.position instead")
            }
            val id = linkAttributeIdOf(key)
            if (id !in mapping.link.attributes) {
                throw LoodsmanApiException("Link attribute '$id' is not configured in mapping.link.attributes")
            }
        }
    }

    /** Идентификатор ключа связи в реестре; ключ проверяется в [validateLinkAttributes] до чтения связей. */
    private fun linkAttributeIdOf(key: LinkAttributeKey): String =
        Registries.linkAttributeKeys.idOf(key)
            ?: throw LoodsmanApiException("Link attribute key '$key' not found in Registries")

    private fun ruleOf(key: AttributeKey): AttributeRule =
        Registries.attributeKeys.idOf(key)?.let { mapping.attributes[it] }
            ?: throw LoodsmanApiException("Attribute '$key' is not configured in mapping.attributes")

    /** Ключевой атрибут типа объекта; тип обязан быть в метаданных Loodsman. */
    private fun keyAttributeOf(snapshot: MetadataSnapshot, id: Int, props: PropObjectOutputDto): String? {
        val typeName = requireNotNull(props.type) { "Loodsman object $id: type is null" }
        if (!snapshot.hasObjectType(typeName)) {
            throw LoodsmanApiException("Object $id: type '$typeName' not found in Loodsman metadata")
        }
        return snapshot.keyAttributeOf(typeName)
    }

    /** Единое правило чтения для [loadTree] и [loadNode]: имена атрибутов разрешаются для вида. */
    private fun attributeRequest(
        kind: PdmNodeKind,
        keyAttribute: String?,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean,
    ): AttributeRequest {
        val keyKey = keyAttribute?.let { name ->
            mapping.attributes.entries.firstOrNull { (_, rule) -> rule.nameFor(kind)?.trim() == name }
                ?.let { (id, _) -> Registries.attributeKeys.resolve(id) }
        }
        return AttributeRequest(
            keyAttribute = keyAttribute,
            keyKey = keyKey,
            names = attributeKeys.associateWith { key -> ruleOf(key).nameFor(kind) },
            mappedNames = mapping.attributes.values.mapNotNullTo(HashSet()) { it.nameFor(kind)?.trim() },
            includeUnmapped = includeUnmapped,
        )
    }

    /** Атрибуты объекта читаются, только если запрос требует не ключевые атрибуты. */
    private suspend fun objectAttributesOf(id: Int, request: AttributeRequest): List<ObjectAttributeOutputDto> =
        if (request.needsObjectAttributes()) api.objectInfo.infoAboutVersion(id) else emptyList()

    private fun toPdmNode(
        prop: PropObjectOutputDto,
        kind: PdmNodeKind,
        attributes: List<ObjectAttributeOutputDto>,
        request: AttributeRequest,
    ): PdmNode = toDomain(prop, kind, attributes, request, mapping.properties.version, mapping.properties.state)

    private suspend fun loadNodes(
        ids: List<Int>,
        snapshot: MetadataSnapshot,
        nodes: MutableMap<Int, LoadedNode>,
        query: StructureQuery,
    ) {
        val props = ids.chunked(PROPS_BATCH_SIZE).flatMap { chunk ->
            api.objectInfo.propObjects(chunk)
        }.associateBy { it.idVersion }

        val prepared = ids.map { id ->
            val prop = props[id] ?: throw LoodsmanApiException("Object not found in Loodsman: id=$id")
            id to Pair(prop, kindMapper.map(prop.type))
        }

        val keyAttributes = prepared.mapNotNull { (id, pair) ->
            pair.second?.let { id to keyAttributeOf(snapshot, id, pair.first) }
        }.toMap()
        val requests = prepared.mapNotNull { (id, pair) ->
            val kind = pair.second ?: return@mapNotNull null
            id to attributeRequest(kind, keyAttributes[id], query.attributes, query.includeUnmappedAttributes)
        }.toMap()
        val attributes = withPermits(requests.keys.toList()) { id -> objectAttributesOf(id, requests.getValue(id)) }

        prepared.forEach { (id, pair) ->
            val (prop, kind) = pair
            nodes[id] = LoadedNode(prop, kind, attributes[id].orEmpty(), keyAttributes[id], requests[id])
        }
    }

    private suspend fun loadLinks(ids: List<Int>, relationByLinkTypeId: Map<Int, Relation>): Map<Int, List<Link>> {
        if (relationByLinkTypeId.isEmpty()) return ids.associateWith { emptyList() }
        val linkTypeIds = relationByLinkTypeId.keys.toList()
        return withPermits(ids) { id ->
            api.objectInfo.linkedObjects(listOf(id), linkTypeIds, inverse = false)
                .mapNotNull { dto -> relationByLinkTypeId[dto.idLinkType]?.let { Link(it, dto) } }
        }
    }

    private suspend fun <T> withPermits(ids: List<Int>, block: suspend (Int) -> T): Map<Int, T> =
        coroutineScope {
            val semaphore = Semaphore(concurrency)
            ids.map { id -> async { id to semaphore.withPermit { block(id) } } }.awaitAll().toMap()
        }

    private suspend fun loadLinkAttributes(
        query: StructureQuery,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>
    ): Map<Int, LinkAttributesData> {
        if (query.linkAttributes.isEmpty() && mapping.link.positionRules.isEmpty()) {
            return emptyMap()
        }

        val linksByLinkId = links.values.flatten().filter { link ->
            nodes[link.dto.idParent]?.kind != null && nodes[link.dto.idChild]?.kind != null
        }.associateBy { it.linkId }

        if (linksByLinkId.isEmpty()) {
            return emptyMap()
        }

        val requestedRules = query.linkAttributes.map { key ->
            mapping.link.attributes.getValue(linkAttributeIdOf(key))
        }

        val attributesByLinkId = withPermits(linksByLinkId.keys.toList()) { linkId ->
            val link = linksByLinkId.getValue(linkId)
            val parentKind = nodes.getValue(link.dto.idParent).kind!!
            val childKind = nodes.getValue(link.dto.idChild).kind!!
            val needsAttributeRequest =
                positionRuleOf(link.relation, parentKind, childKind) != null ||
                        requestedRules.any { it.select(link.relation, parentKind, childKind) != null }

            if (!needsAttributeRequest) {
                return@withPermits emptyMap<String, String>()
            }

            api.objectInfo.linkAttributes(linkId)
                .associate { it.name?.trim().orEmpty() to (it.value?.trim().orEmpty()) }
                .filterKeys { it.isNotEmpty() }
        }

        return attributesByLinkId.mapValues { (_, attributes) -> LinkAttributesData(attributes) }
    }

    private fun buildNode(
        id: Int,
        remaining: Int,
        query: StructureQuery,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>,
        linkAttributes: Map<Int, LinkAttributesData>,
        path: List<Int>
    ): PdmNode {
        if (id in path) {
            throw LoodsmanApiException("Cycle detected in structure: ${(path + id).joinToString(" -> ")}")
        }
        val node = nodes.getValue(id)
        val kind = node.kind
        val request = node.request
        if (kind == null || request == null) {
            throw LoodsmanApiException("Object $id has unmapped type '${node.prop.type}'")
        }

        val usages = if (remaining > 0) {
            links[id].orEmpty().mapNotNull { link ->
                val child = nodes.getValue(link.dto.idChild)
                val childKind = child.kind ?: return@mapNotNull null
                val linkAttrs = linkAttributes[link.linkId]?.attributes ?: emptyMap()
                val attrs = linkAttributesOf(link.relation, kind, childKind, query, linkAttrs)
                val childNode = buildNode(
                    link.dto.idChild, remaining - 1, query, nodes, links, linkAttributes, path + id
                )

                when (link.relation) {
                    Relation.Composition -> {
                        val position = positionRuleOf(link.relation, kind, childKind)?.name
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
                    is Relation.Unmapped -> UnmappedUsage(
                        relation = link.relation,
                        child = childNode,
                        attributes = attrs
                    )
                }
            }
        } else {
            emptyList()
        }

        return toPdmNode(node.prop, kind, node.attributes, request).copy(usages = usages)
    }

    /** Правило позиции для пары видов; позиция читается из [MappingConfig.LinkMapping.positionRules]. */
    private fun positionRuleOf(relation: Relation, parentKind: PdmNodeKind, childKind: PdmNodeKind): LinkAttributeRule? =
        mapping.link.positionRules.select(relation, parentKind, childKind)

    private fun linkAttributesOf(
        relation: Relation,
        parentKind: PdmNodeKind,
        childKind: PdmNodeKind,
        query: StructureQuery,
        linkAttrs: Map<String, String>
    ): Map<LinkAttributeKey, AttributeValue> {
        val result = LinkedHashMap<LinkAttributeKey, AttributeValue>()
        for (key in query.linkAttributes) {
            val rules = mapping.link.attributes.getValue(linkAttributeIdOf(key))
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
            min == null || max == null -> throw LoodsmanApiException(
                "Link ${dto.idLink}: quantity range is half-filled (min=$min, max=$max)"
            )

            min == max -> Quantity.Exact(min)
            else -> Quantity.Range(min, max)
        }
    }

    // Поля количества и единицы заданы в MappingConfig.validate: quantityMin/quantityMax обязаны быть числовыми,
    // unit — текстовым, поэтому недостижимые для каждой функции виды не имеют ветки результата.
    private fun GetLinkedObjectsForObjectsOutputDto_V4.number(field: LinkField): Double? = when (field) {
        LinkField.MIN_QUANTITY -> minQuantity
        LinkField.MAX_QUANTITY -> maxQuantity
        LinkField.UNIT -> error("Link field '$field' is not numeric: rejected by MappingConfig.validate")
    }

    private fun GetLinkedObjectsForObjectsOutputDto_V4.text(field: LinkField): String? = when (field) {
        LinkField.UNIT -> unit?.trim()?.takeIf { it.isNotEmpty() }
        LinkField.MIN_QUANTITY, LinkField.MAX_QUANTITY ->
            error("Link field '$field' is not text: rejected by MappingConfig.validate")
    }

    private class LoadedNode(
        val prop: PropObjectOutputDto,
        val kind: PdmNodeKind?,
        val attributes: List<ObjectAttributeOutputDto>,
        val keyAttribute: String?,
        /** Запрос атрибутов вычисляется один раз при загрузке узла; для узлов без вида не задан. */
        val request: AttributeRequest?,
    )

    private class Link(
        val relation: Relation,
        val dto: GetLinkedObjectsForObjectsOutputDto_V4,
        val linkId: Int = dto.idLink
    )

    private data class LinkAttributesData(
        val attributes: Map<String, String>
    )
}
