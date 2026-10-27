package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.MadeFromUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.TechnologicalPartUsage
import dev.reportgenerator.domain.UnmappedUsage
import dev.reportgenerator.loodsman.mapping.LinkField
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.select
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4

/** Сборка дерева [PdmNode] из загруженных узлов и связей: usages, атрибуты связей, количество и единица. */
internal class TreeAssembler(
    private val mapping: MappingConfig,
    private val nodeLoader: NodeLoader,
    private val linkAttributeLoader: LinkAttributeLoader,
) {

    fun buildNode(
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
                        val position = linkAttributeLoader.positionRuleOf(link.relation, kind, childKind)?.name
                            ?.let { posName -> linkAttrs[posName]?.toIntOrNull() }
                            ?.let { Field.Value(it) } ?: Field.Missing
                        CompositionUsage(
                            target = childNode,
                            position = position,
                            quantity = quantityOf(link.dto),
                            unit = link.dto.text(mapping.link.fields.unit)?.let { Field.Value(it) } ?: Field.Missing,
                            attributes = attrs
                        )
                    }

                    Relation.Document -> DocumentUsage(target = childNode, attributes = attrs)
                    Relation.MadeFrom -> MadeFromUsage(target = childNode, attributes = attrs)
                    Relation.TechnologicalPart -> TechnologicalPartUsage(target = childNode, attributes = attrs)
                    is Relation.Unmapped -> UnmappedUsage(
                        relation = link.relation,
                        target = childNode,
                        attributes = attrs
                    )
                }
            }
        } else {
            emptyList()
        }

        return nodeLoader.toPdmNode(node.prop, kind, node.attributes, request).copy(usages = usages)
    }

    private fun linkAttributesOf(
        relation: Relation,
        parentKind: PdmNodeKind,
        childKind: PdmNodeKind,
        query: StructureQuery,
        linkAttrs: Map<String, String>
    ): Map<LinkAttributeKey, AttributeValue> {
        val result = LinkedHashMap<LinkAttributeKey, AttributeValue>()
        for (key in query.linkAttributes) {
            val rules = mapping.link.attributes.getValue(linkAttributeLoader.linkAttributeIdOf(key))
            val rule = rules.select(relation, parentKind, childKind)
            val value = rule?.name?.let { name -> linkAttrs[name] }?.takeIf { it.isNotEmpty() }
            result[key] = value?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
        }
        return result
    }

    internal fun quantityOf(dto: GetLinkedObjectsForObjectsOutputDto_V4): Quantity {
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
        LinkField.UNIT -> unit?.takeIf { it.isNotEmpty() }
        LinkField.MIN_QUANTITY, LinkField.MAX_QUANTITY ->
            error("Link field '$field' is not text: rejected by MappingConfig.validate")
    }
}
