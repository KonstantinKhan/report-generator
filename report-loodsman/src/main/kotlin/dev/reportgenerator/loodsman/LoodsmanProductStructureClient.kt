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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Число одновременных запросов к Loodsman по умолчанию. Нагрузка задержко-зависимая: запросы идут через туннель,
 * поэтому при высокой задержке ответа лимит 8 оставляет канал простаивающим.
 */
internal const val DEFAULT_CONCURRENCY = 24

/**
 * Клиент структуры изделия поверх Loodsman. Кэш и лимит запросов живут в [LoadSession]: вне сессии каждый вызов
 * [loadTree] / [loadTrees] открывает свою, внутри [loadSession] вызовы делят одну.
 */
class LoodsmanProductStructureClient internal constructor(
    api: LoodsmanApi,
    private val mapping: MappingConfig,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val metadata: MetadataSource
) : PdmNodeStructureClient {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val kindMapper = KindMapper(mapping, log)
    private val relationMapper = RelationMapper(mapping, log)
    private val attributeRequests = AttributeRequests(mapping)
    private val nodeLoader = NodeLoader(api, mapping, kindMapper, attributeRequests)
    private val linkLoader = LinkLoader(api)
    private val linkAttributeLoader = LinkAttributeLoader(api, mapping)
    private val replacementLoader = ReplacementLoader(api)
    private val treeAssembler = TreeAssembler(mapping, nodeLoader, linkAttributeLoader)

    override suspend fun <T> loadSession(block: suspend () -> T): T {
        if (coroutineContext[LoadSession] != null) return block()
        return coroutineScope {
            val scope = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob(coroutineContext[Job]))
            val session = LoadSession(concurrency, scope)
            try {
                withContext(session) { block() }
            } finally {
                session.close()
            }
        }
    }

    override suspend fun loadTree(query: StructureQuery): PdmNode = loadTrees(listOf(query)).single()

    override suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode> = loadSession {
        val session = checkNotNull(coroutineContext[LoadSession]) { "loadSession must open the load session" }
        coroutineScope {
            queries.map { query -> async { loadTreeIn(session, query) } }.awaitAll()
        }
    }

    private suspend fun loadTreeIn(session: LoadSession, query: StructureQuery): PdmNode {
        val rootId = query.rootId.asInt()
        val snapshot = metadata.snapshot()
        val linkTypes = resolveLinkTypes(snapshot, query.relations)
        query.attributes.forEach { attributeRequests.ruleOf(it) }
        validateLinkAttributes(query)
        validateRelationDepths(query)
        validateReplacementDepth(query)

        val state = TraversalState()
        var frontier = listOf(rootId)
        var level = 0
        // Предел раскрытия по связи: relationDepth режет связь раньше общего depth, пустая запись — depth.
        val effectiveDepths = query.relations.associateWith { relation ->
            minOf(query.depth, query.relationDepth[relation] ?: query.depth)
        }
        while (frontier.isNotEmpty()) {
            nodeLoader.loadNodes(session, frontier, snapshot, state.nodes, query)
            if (level >= query.depth) break

            // Дети следующего уровня по связи появляются, только если уровень не глубже её предела.
            val levelLinkTypes = linkTypes.filter { level + 1 <= effectiveDepths.getValue(it.rule.relation) }
            if (levelLinkTypes.isEmpty()) break

            val expandable = frontier.filter { state.nodes.getValue(it).kind != null }
            // Тип узла Loodsman нужен для отбора правил: раскрываемый узел имеет вид, значит тип задан.
            val typeNameById = expandable.associateWith { requireNotNull(state.nodes.getValue(it).prop.type) }
            state.links.putAll(linkLoader.loadLinks(session, typeNameById, levelLinkTypes))
            frontier = expandable.flatMap { id -> state.links.getValue(id).map { it.dto.idChild } }
                .distinct()
                .filter { it !in state.nodes }
            level++
        }

        val root = state.nodes.getValue(rootId)
        if (root.kind == null) {
            throw LoodsmanApiException("Root object $rootId has unmapped type '${root.prop.type}'")
        }

        val linkAttributes = linkAttributeLoader.loadLinkAttributes(session, query, snapshot, state.nodes, state.links)
        val replacements = replacementLoader.loadReplacements(session, query, rootId, state.nodes, state.links)
        return treeAssembler.buildNode(
            rootId, query.depth, query, state.nodes, state.links, linkAttributes, replacements, emptyList()
        )
    }

    override suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean
    ): PdmNode {
        val snapshot = metadata.snapshot()
        return nodeLoader.loadNode(id, snapshot, attributeKeys, includeUnmapped)
    }

    private fun resolveLinkTypes(snapshot: MetadataSnapshot, relations: Set<Relation>): List<LinkTypeRule> {
        val rules = relationMapper.rulesFor(relations)
        relations.forEach { relation ->
            if (rules.none { it.relation == relation }) {
                throw LoodsmanApiException("Relation $relation is not configured in mapping")
            }
        }
        return rules.map { rule ->
            val linkTypeId = snapshot.linkTypeId(rule.link)
                ?: throw LoodsmanApiException("Link type '${rule.link}' not found in Loodsman metadata")
            LinkTypeRule(linkTypeId, rule)
        }
    }

    private fun validateRelationDepths(query: StructureQuery) {
        for ((relation, limit) in query.relationDepth) {
            if (relation !in query.relations) {
                throw LoodsmanApiException("relationDepth contains '$relation' which is not in the query relations")
            }
            if (limit < 1) {
                throw LoodsmanApiException("relationDepth for '$relation' must be at least 1, got $limit")
            }
        }
    }

    private fun validateReplacementDepth(query: StructureQuery) {
        if (query.replacementDepth < 0) {
            throw LoodsmanApiException("replacementDepth must not be negative, got ${query.replacementDepth}")
        }
        if (!query.replacements && query.replacementDepth != 0) {
            throw LoodsmanApiException("replacementDepth is set without replacements = true")
        }
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
