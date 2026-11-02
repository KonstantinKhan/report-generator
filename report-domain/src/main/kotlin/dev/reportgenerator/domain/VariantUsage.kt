package dev.reportgenerator.domain

// Исполнение изделия: target — другое исполнение того же изделия (прямая связь от основного исполнения).
data class VariantUsage(
    override val target: PdmNode,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap()
) : Usage {
    override val relation: Relation get() = Relation.Variant
}
