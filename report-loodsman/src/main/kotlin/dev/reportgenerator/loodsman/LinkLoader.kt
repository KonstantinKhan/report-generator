package dev.reportgenerator.loodsman

import dev.reportgenerator.loodsman.mapping.LinkDirection
import dev.reportgenerator.loodsman.mapping.ResolvedRelationRule
import io.github.konstantinkhan.loodsman.LoodsmanApi

/** Правило связи с разрешённым id типа связи Loodsman. */
internal class LinkTypeRule(val linkTypeId: Int, val rule: ResolvedRelationRule)

/** Загрузка связей объектов по правилам связей; результат по id запрошенного объекта. */
internal class LinkLoader(
    private val api: LoodsmanApi,
    private val limiter: Limiter,
) {

    /**
     * [typeNameById] — id раскрываемых узлов и их типы Loodsman; [linkTypes] — правила с id типов связей.
     * Для каждого узла правила отбираются по типу, группируются по направлению и читаются одним вызовом на группу.
     *
     * Факт с живого сервера: `get-linked-objects-for-objects` с `inverse=true` для объекта X возвращает записи,
     * где `idParent` = X (запрошенный объект), а `idChild` = сосед на другом конце связи. API смотрит с точки
     * зрения запрошенного объекта, поэтому обход и TreeAssembler работают с `idChild` как с целью без изменений.
     */
    suspend fun loadLinks(typeNameById: Map<Int, String>, linkTypes: List<LinkTypeRule>): Map<Int, List<Link>> {
        if (linkTypes.isEmpty()) return typeNameById.mapValues { emptyList<Link>() }
        return limiter.withPermits(typeNameById.keys.toList()) { id ->
            val typeName = typeNameById.getValue(id)
            linkTypes
                .filter { it.rule.appliesTo(typeName) }
                .groupBy { it.rule.direction }
                .flatMap { (direction, group) -> loadGroup(id, direction, group) }
        }
    }

    private suspend fun loadGroup(id: Int, direction: LinkDirection, group: List<LinkTypeRule>): List<Link> {
        val relationByLinkTypeId = group.associate { it.linkTypeId to it.rule.relation }
        return api.objectInfo.linkedObjects(
            listOf(id),
            relationByLinkTypeId.keys.toList(),
            inverse = direction == LinkDirection.INVERSE,
        ).mapNotNull { dto -> relationByLinkTypeId[dto.idLinkType]?.let { Link(it, dto) } }
    }
}
