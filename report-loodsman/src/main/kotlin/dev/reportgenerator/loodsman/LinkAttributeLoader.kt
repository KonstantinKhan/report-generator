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

    suspend fun loadLinkAttributes(
        session: LoadSession,
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

        val needed = linksByLinkId.values.filter { link ->
            val parentKind = nodes.getValue(link.dto.idParent).kind!!
            val childKind = nodes.getValue(link.dto.idChild).kind!!
            positionRuleOf(link.relation, parentKind, childKind) != null ||
                    requestedRules.any { it.select(link.relation, parentKind, childKind) != null }
        }.map { it.linkId }

        val loaded = session.linkAttributes.getAll(needed) { missing ->
            val results = session.limiter.mapPermitted(missing) { linkId -> api.objectInfo.linkAttributes(linkId) }
            missing.zip(results).toMap()
        }

        return linksByLinkId.keys.associateWith { linkId ->
            LinkAttributesData(
                loaded[linkId].orEmpty()
                    .associate { it.name.orEmpty() to it.value.orEmpty() }
                    .filterKeys { it.isNotEmpty() }
            )
        }
    }
}
