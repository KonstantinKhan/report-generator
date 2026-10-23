package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Relation
import io.github.konstantinkhan.loodsman.LoodsmanApi

/** Загрузка связей объектов по типам связей; результат по id родителя. */
internal class LinkLoader(
    private val api: LoodsmanApi,
    private val limiter: Limiter,
) {

    suspend fun loadLinks(ids: List<Int>, relationByLinkTypeId: Map<Int, Relation>): Map<Int, List<Link>> {
        if (relationByLinkTypeId.isEmpty()) return ids.associateWith { emptyList() }
        val linkTypeIds = relationByLinkTypeId.keys.toList()
        return limiter.withPermits(ids) { id ->
            api.objectInfo.linkedObjects(listOf(id), linkTypeIds, inverse = false)
                .mapNotNull { dto -> relationByLinkTypeId[dto.idLinkType]?.let { Link(it, dto) } }
        }
    }
}
