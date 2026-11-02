package dev.reportgenerator.domain

data class CompositionUsage(
    override val target: PdmNode,
    val position: Field<Int>,
    val quantity: Quantity,
    val unit: Field<String>,
    override val attributes: Map<LinkAttributeKey, AttributeValue> = emptyMap(),
    /** id связи состава в Loodsman; нужен для привязки групп замен. null — связь не загружена (например, в тестах). */
    val linkId: Int? = null,
) : Usage {
    override val relation: Relation get() = Relation.Composition
}
