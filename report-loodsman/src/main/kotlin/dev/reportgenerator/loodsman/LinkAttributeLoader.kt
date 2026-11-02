package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.loodsman.mapping.LinkAttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.Registries
import dev.reportgenerator.loodsman.mapping.select
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.GetOriginalLinkAttributesOutputDto

/**
 * Число связей в одном запросе атрибутов: пачка идёт одним `get-original-link-attributes`.
 * Короче запросы, больше параллельных: ответ на пачку мал и не упирается в таймаут на сервере.
 */
internal const val LINK_ATTRIBUTES_BATCH_SIZE = 30

/**
 * Загрузка атрибутов связей. Атрибуты читаются только у связей, для которых запрошен атрибут
 * или есть правило позиции.
 */
internal class LinkAttributeLoader(
    private val api: LoodsmanApi,
    private val mapping: MappingConfig,
) {

    /** Идентификатор ключа связи в реестре; ключ проверяется в клиенте до чтения связей. */
    fun linkAttributeIdOf(key: LinkAttributeKey): String =
        Registries.linkAttributeKeys.idOf(key)
            ?: throw LoodsmanApiException("Link attribute key '$key' not found in Registries")

    /** Правило позиции для пары видов; позиция читается из [MappingConfig.LinkMapping.positionRules]. */
    fun positionRuleOf(relation: Relation, parentKind: PdmNodeKind, childKind: PdmNodeKind): LinkAttributeRule? =
        mapping.link.positionRules.select(relation, parentKind, childKind)

    /** Связь участвует в загрузке атрибутов, когда оба её конца загружены и имеют вид. */
    fun bothEndsLoaded(link: Link, nodes: Map<Int, LoadedNode>): Boolean =
        nodes[link.dto.idParent]?.kind != null && nodes[link.dto.idChild]?.kind != null

    /**
     * Атрибуты связей пачки [links]; каждая связь должна проходить [bothEndsLoaded]. Вызывается по мере появления
     * связей, поэтому пачки формируются внутри одной порции связей, а не по всему дереву.
     */
    suspend fun loadLinkAttributes(
        session: LoadSession,
        query: StructureQuery,
        snapshot: MetadataSnapshot,
        nodes: Map<Int, LoadedNode>,
        links: List<Link>,
    ): Map<Int, LinkAttributesData> {
        if (query.linkAttributes.isEmpty() && mapping.link.positionRules.isEmpty()) {
            return emptyMap()
        }

        val linksByLinkId = links.associateBy { it.linkId }

        if (linksByLinkId.isEmpty()) {
            return emptyMap()
        }

        val requestedRules = query.linkAttributes.map { key ->
            mapping.link.attributes.getValue(linkAttributeIdOf(key))
        }

        val needed = linksByLinkId.values.filter { link ->
            val parentKind = nodes.getValue(link.dto.idParent).kind!!
            val childKind = nodes.getValue(link.dto.idChild).kind!!
            positionRuleOf(link.relation, parentKind, childKind) != null ||
                    requestedRules.any { it.select(link.relation, parentKind, childKind) != null }
        }.map { it.linkId }

        // Id считаются один раз на вызов, а не на пачку. null — имена не найдены в снимке: читаем без фильтра.
        val attrIds = linkAttributeIdsOf(snapshot, positionRules = mapping.link.positionRules, requestedRules = requestedRules)

        val loaded = session.linkAttributes.getAll(needed) { missing ->
            // Пачка — один запрос; связь без строк в ответе получает пустой список, иначе кэш сочтёт её ошибкой.
            val rows = session.limiter.mapPermitted(missing.chunked(LINK_ATTRIBUTES_BATCH_SIZE)) { batch ->
                api.objectInfo.originalLinkAttributes(batch, attrIds = attrIds)
            }.flatten()
            val rowsByLinkId = rows.groupBy { it.idLink }
            missing.associateWith { linkId -> rowsByLinkId[linkId].orEmpty() }
        }

        return linksByLinkId.keys.associateWith { linkId ->
            LinkAttributesData(
                loaded[linkId].orEmpty()
                    .associate { it.attrName.orEmpty() to it.value.orEmpty() }
                    .filterKeys { it.isNotEmpty() }
            )
        }
    }

    /**
     * Id атрибутов, которые читает загрузка: имена правил позиции и запрошенных атрибутов.
     * `null`, если ни одно имя не найдено в снимке, — тогда фильтр не передаётся, чтобы результат не потерялся.
     * Клиентский фильтр по `attrName` остаётся страховкой и в том случае, когда фильтр передан.
     */
    private fun linkAttributeIdsOf(
        snapshot: MetadataSnapshot,
        positionRules: List<LinkAttributeRule>,
        requestedRules: List<List<LinkAttributeRule>>,
    ): List<Int>? {
        val names = (positionRules + requestedRules.flatten()).map { it.name }
        return snapshot.attributeIdsOf(names).takeIf { it.isNotEmpty() }
    }
}
