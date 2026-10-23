package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.RelationMapper
import org.slf4j.LoggerFactory
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException

class LoodsmanProductStructureClient internal constructor(
    private val api: LoodsmanApi,
    private val mapping: MappingConfig,
    private val concurrency: Int = 8,
    private val metadata: MetadataSource
) : PdmNodeStructureClient {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val kindMapper = KindMapper(mapping, log)
    private val relationMapper = RelationMapper(mapping, log)
    private val limiter = Limiter(concurrency)
    private val attributeRequests = AttributeRequests(mapping)
    private val nodeLoader = NodeLoader(api, mapping, kindMapper, attributeRequests, limiter)
    private val linkLoader = LinkLoader(api, limiter)
    private val linkAttributeLoader = LinkAttributeLoader(api, mapping, limiter)
    private val treeAssembler = TreeAssembler(mapping, nodeLoader, linkAttributeLoader)

    override suspend fun loadTree(query: StructureQuery): PdmNode {
        val rootId = query.rootId.asInt()
        val snapshot = metadata.snapshot()
        val relationByLinkTypeId = resolveLinkTypes(snapshot, query.relations)
        query.attributes.forEach { attributeRequests.ruleOf(it) }
        validateLinkAttributes(query)

        val state = TraversalState()
        var frontier = listOf(rootId)
        var level = 0
        while (frontier.isNotEmpty()) {
            nodeLoader.loadNodes(frontier, snapshot, state.nodes, query)
            if (level >= query.depth) break

            val expandable = frontier.filter { state.nodes.getValue(it).kind != null }
            state.links.putAll(linkLoader.loadLinks(expandable, relationByLinkTypeId))
            frontier = expandable.flatMap { id -> state.links.getValue(id).map { it.dto.idChild } }
                .distinct()
                .filter { it !in state.nodes }
            level++
        }

        val root = state.nodes.getValue(rootId)
        if (root.kind == null) {
            throw LoodsmanApiException("Root object $rootId has unmapped type '${root.prop.type}'")
        }

        val linkAttributes = linkAttributeLoader.loadLinkAttributes(query, state.nodes, state.links)
        return treeAssembler.buildNode(rootId, query.depth, query, state.nodes, state.links, linkAttributes, emptyList())
    }

    override suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean
    ): PdmNode {
        val snapshot = metadata.snapshot()
        return nodeLoader.loadNode(id, snapshot, attributeKeys, includeUnmapped)
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
            val id = linkAttributeLoader.linkAttributeIdOf(key)
            if (id !in mapping.link.attributes) {
                throw LoodsmanApiException("Link attribute '$id' is not configured in mapping.link.attributes")
            }
        }
    }
}
