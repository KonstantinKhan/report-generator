package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.VariantUsage
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.RelationMapper
import org.slf4j.LoggerFactory
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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

    /**
     * Дерево одного запроса. Атрибуты связей и замены стартуют по мере готовности своих данных, а не после обхода:
     * - атрибуты связи — когда загружены оба её конца (см. [LinkAttributeLoader.bothEndsLoaded]);
     * - замены — когда раскрыты связи состава уровней 0..[ReplacementLoader.lastLevel].
     * Снимки узлов и связей передаются в эти загрузки копиями: обход продолжает менять свои таблицы.
     * Любая ошибка отменяет остальные загрузки дерева и пробрасывается как есть.
     */
    private suspend fun loadTreeIn(session: LoadSession, query: StructureQuery): PdmNode = coroutineScope {
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
        val linkAttributeLoads = mutableListOf<Deferred<Map<Int, LinkAttributesData>>>()
        val scheduledLinkIds = HashSet<Int>()
        var replacementLoad: Deferred<Map<Int, List<ReplacementGroup>>>? = null
        val replacementsLastLevel = replacementLoader.lastLevel(query)

        // Связи, у которых оба конца уже загружены и которые ещё не отправлены на чтение атрибутов.
        fun scheduleLinkAttributes() {
            val fresh = state.links.values.flatten()
                .filter { it.linkId !in scheduledLinkIds && linkAttributeLoader.bothEndsLoaded(it, state.nodes) }
                .distinctBy { it.linkId }
            if (fresh.isEmpty()) return
            scheduledLinkIds += fresh.map { it.linkId }
            val nodesSnapshot = state.nodes.toMap()
            linkAttributeLoads += async { linkAttributeLoader.loadLinkAttributes(session, query, snapshot, nodesSnapshot, fresh) }
        }

        fun startReplacements() {
            if (replacementLoad != null) return
            val nodesSnapshot = state.nodes.toMap()
            val linksSnapshot = state.links.toMap()
            replacementLoad = async {
                replacementLoader.loadReplacements(session, query, rootId, nodesSnapshot, linksSnapshot)
            }
        }

        while (frontier.isNotEmpty()) {
            nodeLoader.loadNodes(session, frontier, snapshot, state.nodes, query)
            scheduleLinkAttributes()
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
            // Связи уровней 0..lastLevel раскрыты: замены не зависят от дальнейшего обхода.
            if (query.replacements && level > replacementsLastLevel) startReplacements()
        }
        // Связи последнего раскрытия: если их дети уже были в nodes, цикл не делал новой загрузки узлов и готовность проверяется здесь.
        scheduleLinkAttributes()
        if (query.replacements) startReplacements()

        val root = state.nodes.getValue(rootId)
        if (root.kind == null) {
            throw LoodsmanApiException("Root object $rootId has unmapped type '${root.prop.type}'")
        }

        val linkAttributes = mutableMapOf<Int, LinkAttributesData>()
        linkAttributeLoads.awaitAll().forEach { linkAttributes.putAll(it) }
        val replacements = replacementLoad?.await() ?: emptyMap()
        treeAssembler.buildNode(
            rootId, query.depth, query, state.nodes, state.links, linkAttributes, replacements, emptyList()
        )
    }

    /**
     * Исполнения корня и их полные деревья в одной сессии: корень ([loadTreeIn]) и исполнения стартуют параллельно.
     * Id исполнений берутся из [variantIdsOf] (один запрос связей корня по связи исполнений), поэтому исполнения не
     * ждут окончания обхода корня. Результат тот же, что у дефолтной реализации: порядок usages, дети без вида вне.
     */
    override suspend fun loadTreeWithVariants(
        rootQuery: StructureQuery,
        variantQuery: (PdmNodeId) -> StructureQuery,
    ): PdmNode = loadSession {
        val session = checkNotNull(coroutineContext[LoadSession]) { "loadSession must open the load session" }
        coroutineScope {
            val root = async { loadTreeIn(session, rootQuery) }
            val variantIds = variantIdsOf(session, rootQuery)
            val full = if (variantIds.isEmpty()) {
                emptyMap()
            } else {
                variantIds.zip(loadTrees(variantIds.map(variantQuery))).toMap()
            }
            val tree = root.await()
            if (full.isEmpty()) return@coroutineScope tree
            tree.copy(usages = tree.usages.map { usage ->
                if (usage is VariantUsage) usage.copy(target = full.getValue(usage.target.id)) else usage
            })
        }
    }

    /**
     * Id исполнений корня так же, как их даёт обход [loadTreeIn]: связь исполнений глубиной не меньше 1 и корень
     * с видом. Один запрос связей корня по связи исполнений; свойства детей читаются для отбора по виду (кэш сессии
     * общий с обходом). Дети без вида отбрасываются, как в [TreeAssembler]. Запрос не из кэша обхода: ключ связей
     * включает все типы связей прямой группы, поэтому обход корня повторяет связь исполнений (один лишний запрос).
     */
    private suspend fun variantIdsOf(session: LoadSession, query: StructureQuery): List<PdmNodeId> {
        val variantDepth = minOf(query.depth, query.relationDepth[Relation.Variant] ?: query.depth)
        if (Relation.Variant !in query.relations || variantDepth < 1) return emptyList()

        val snapshot = metadata.snapshot()
        val variantRules = resolveLinkTypes(snapshot, setOf(Relation.Variant))
        val id = query.rootId.asInt()
        val rootProp = session.props.getAll(listOf(id)) { missing -> nodeLoader.loadProps(session, missing) }.getValue(id)
        val rootType = rootProp.type
        if (rootType == null || kindMapper.map(rootType) == null) return emptyList()

        val childIds = linkLoader.loadLinks(session, mapOf(id to rootType), variantRules)[id].orEmpty()
            .map { it.dto.idChild }
        val childProps = session.props.getAll(childIds.distinct()) { missing -> nodeLoader.loadProps(session, missing) }
        return childIds.filter { childId -> kindMapper.map(childProps.getValue(childId).type) != null }
            .map { PdmNodeId(it) }
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
