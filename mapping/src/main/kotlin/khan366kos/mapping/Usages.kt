package khan366kos.mapping

import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.UnmappedUsage
import dev.reportgenerator.domain.Usage

data class ResolvedLink(
    val relation: Relation,
    val child: PdmNode,
    val quantity: Quantity,
    val unit: Field<String>,
    val positionValue: String?,
    val attributes: Map<LinkAttributeKey, String?>
)

fun usagesOf(links: List<ResolvedLink>): List<Usage> = links.map { link ->
    val attrs = link.attributes.mapValues { (_, v) ->
        v?.let(AttributeValue::Text) ?: AttributeValue.Empty
    }
    when (link.relation) {
        Relation.Composition -> CompositionUsage(
            child = link.child,
            position = link.positionValue?.toIntOrNull()?.let { Field.Value(it) } ?: Field.Missing,
            quantity = link.quantity,
            unit = link.unit,
            attributes = attrs,
        )

        Relation.Document -> DocumentUsage(child = link.child, attributes = attrs)
        is Relation.Unmapped -> UnmappedUsage(
            relation = link.relation,
            child = link.child,
            attributes = attrs,
        )
    }
}