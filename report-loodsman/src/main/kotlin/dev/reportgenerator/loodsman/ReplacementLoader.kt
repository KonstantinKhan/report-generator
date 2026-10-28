package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.ReplacementType
import dev.reportgenerator.domain.ReplacementVariant
import dev.reportgenerator.domain.StructureQuery
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.V3_GetInfoAboutVersionMode20OutputDto

/**
 * Загрузка групп замен (допускаемые замены) для узлов дерева. Строки ответа группируются по группе и варианту,
 * члены варианта — по id связи состава; все члены сохраняются, отбор по usages узла делается в спецификации.
 */
internal class ReplacementLoader(
    private val api: LoodsmanApi,
) {

    /**
     * Группы замен по id узла. Запросы только если [StructureQuery.replacements] = true.
     * Узлы — уровни 0..min(replacementDepth, depth - 1): на уровне depth связи состава не раскрыты,
     * сопоставлять замены не с чем, поэтому такие узлы не запрашиваются. Запрос только для узлов,
     * у которых есть загруженная связь состава (замены относятся к usages состава).
     */
    suspend fun loadReplacements(
        session: LoadSession,
        query: StructureQuery,
        rootId: Int,
        nodes: Map<Int, LoadedNode>,
        links: Map<Int, List<Link>>,
    ): Map<Int, List<ReplacementGroup>> {
        if (!query.replacements) return emptyMap()
        val maxLevel = minOf(query.replacementDepth, query.depth - 1)
        if (maxLevel < 0) return emptyMap()

        val ids = nodesWithinLevel(rootId, maxLevel, links).filter { id ->
            nodes[id]?.kind != null && links[id].orEmpty().any { it.relation == Relation.Composition }
        }
        if (ids.isEmpty()) return emptyMap()

        val rows = session.replacements.getAll(ids) { missing ->
            val results = session.limiter.mapPermitted(missing) { id -> api.objectInfo.replacementVariants(id) }
            missing.zip(results).toMap()
        }
        return ids.associateWith { id -> groupsOf(rows.getValue(id)) }
    }

    /**
     * Узлы дерева уровней 0..[maxLevel] от корня, обход только по связям состава по уже загруженным связям;
     * каждый id один раз.
     */
    private fun nodesWithinLevel(rootId: Int, maxLevel: Int, links: Map<Int, List<Link>>): List<Int> {
        val reached = LinkedHashSet<Int>().apply { add(rootId) }
        var frontier = listOf(rootId)
        var level = 0
        while (frontier.isNotEmpty() && level < maxLevel) {
            frontier = frontier.flatMap { id ->
                links[id].orEmpty().filter { it.relation == Relation.Composition }.map { it.dto.idChild }
            }
                .filter { reached.add(it) }
            level++
        }
        return reached.toList()
    }

    companion object {
        /** Строки одного ответа в группы и варианты; порядок строк ответа сохраняется. */
        internal fun groupsOf(rows: List<V3_GetInfoAboutVersionMode20OutputDto>): List<ReplacementGroup> =
            rows.groupBy { it.idGroupChange }.map { (groupId, groupRows) ->
                val first = groupRows.first()
                ReplacementGroup(
                    id = groupId,
                    name = first.groupChangeName,
                    type = typeOf(groupId, first.groupChangeType),
                    variants = groupRows.groupBy { it.idVariant }.map { (variantId, variantRows) ->
                        val firstVariant = variantRows.first()
                        ReplacementVariant(
                            id = variantId,
                            name = firstVariant.variantName,
                            basic = firstVariant.basic == 1,
                            linkIds = variantRows.map { it.idLink }.distinct(),
                            inCurrentConfiguration = firstVariant.variantInCurrentConf == 1,
                        )
                    },
                    inCurrentConfiguration = first.groupInCurrentConf == 1,
                )
            }

        /** Код типа группы Loodsman: 0 — потребитель, 1 — производство, 2 — допускаемая замена. */
        internal fun typeOf(groupId: Int, code: Int): ReplacementType = when (code) {
            0 -> ReplacementType.Consumer
            1 -> ReplacementType.Production
            2 -> ReplacementType.Permissible
            else -> throw LoodsmanApiException("Replacement group $groupId: unknown groupChangeType $code")
        }
    }
}
