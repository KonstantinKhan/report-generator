package dev.reportgenerator.domain

data class PdmNode(
    val id: PdmNodeId,
    val kind: PdmNodeKind,
    val version: Field<String> = Field.NotLoaded,
    val state: Field<String> = Field.NotLoaded,
    val attributes: Map<AttributeKey, AttributeValue> = emptyMap(),
    val usages: List<Usage> = emptyList(),
    val key: NodeKey = NodeKey.NotLoaded,
)
