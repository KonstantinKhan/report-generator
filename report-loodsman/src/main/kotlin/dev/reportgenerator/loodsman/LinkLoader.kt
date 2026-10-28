package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Relation
import dev.reportgenerator.loodsman.mapping.LinkDirection
import dev.reportgenerator.loodsman.mapping.ResolvedRelationRule
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4

/** Правило связи с разрешённым id типа связи Loodsman. */
internal class LinkTypeRule(val linkTypeId: Int, val rule: ResolvedRelationRule)

/** Размер пачки объектов в одном запросе get-linked-objects-for-objects. */
internal const val LINKS_BATCH_SIZE = 100

/**
 * Загрузка связей объектов по правилам связей; результат по id запрошенного объекта.
 * Запросы группируются по набору типов связей и направлению, объекты одной группы идут пачками по [LINKS_BATCH_SIZE].
 */
internal class LinkLoader(
    private val api: LoodsmanApi,
) {

    /** Группа правил одного объекта: ключ запроса и соответствие id типа связи → вид связи. */
    private class GroupQuery(val key: LinksKey, val relationByLinkTypeId: Map<Int, Relation>)

    /**
     * [typeNameById] — id раскрываемых узлов и их типы Loodsman; [linkTypes] — правила с id типов связей.
     * Для каждого узла правила отбираются по типу и группируются по направлению; порядок групп и связей в ответе
     * сохраняется.
     *
     * Факт с живого сервера: `get-linked-objects-for-objects` с `inverse=true` для объекта X возвращает записи,
     * где `idParent` = X (запрошенный объект), а `idChild` = сосед на другом конце связи. API смотрит с точки
     * зрения запрошенного объекта, поэтому обход и TreeAssembler работают с `idChild` как с целью без изменений.
     */
    suspend fun loadLinks(
        session: LoadSession,
        typeNameById: Map<Int, String>,
        linkTypes: List<LinkTypeRule>,
    ): Map<Int, List<Link>> {
        if (linkTypes.isEmpty()) return typeNameById.mapValues { emptyList<Link>() }

        val groupsById = typeNameById.mapValues { (_, typeName) ->
            linkTypes
                .filter { it.rule.appliesTo(typeName) }
                .groupBy { it.rule.direction }
                .map { (direction, group) ->
                    GroupQuery(
                        key = LinksKey(
                            parentId = 0,
                            linkTypeIds = group.map { it.linkTypeId }.sorted(),
                            inverse = direction == LinkDirection.INVERSE,
                        ),
                        relationByLinkTypeId = group.associate { it.linkTypeId to it.rule.relation },
                    )
                }
        }
        val keysById = groupsById.mapValues { (id, groups) -> groups.map { it.key.copy(parentId = id) } }
        val loaded = session.links.getAll(keysById.values.flatten()) { missing -> fetch(session, missing) }

        return groupsById.mapValues { (id, groups) ->
            groups.flatMap { group ->
                loaded.getValue(group.key.copy(parentId = id)).mapNotNull { dto ->
                    group.relationByLinkTypeId[dto.idLinkType]?.let { Link(it, dto) }
                }
            }
        }
    }

    /** Связи по ключам одной загрузки: пачки объектов с одинаковыми типами связей и направлением. */
    private suspend fun fetch(
        session: LoadSession,
        missing: List<LinksKey>,
    ): Map<LinksKey, List<GetLinkedObjectsForObjectsOutputDto_V4>> {
        val batches = missing.groupBy { it.linkTypeIds to it.inverse }.flatMap { (request, keys) ->
            keys.map { it.parentId }.chunked(LINKS_BATCH_SIZE).map { ids -> request to ids }
        }
        val responses = session.limiter.mapPermitted(batches) { (request, ids) ->
            api.objectInfo.linkedObjects(ids, request.first, inverse = request.second)
        }
        val result = HashMap<LinksKey, List<GetLinkedObjectsForObjectsOutputDto_V4>>()
        batches.forEachIndexed { index, (request, ids) ->
            val rowsByParent = responses[index].groupBy { it.idParent }
            ids.forEach { id ->
                result[LinksKey(id, request.first, request.second)] = rowsByParent[id].orEmpty()
            }
        }
        return result
    }
}
