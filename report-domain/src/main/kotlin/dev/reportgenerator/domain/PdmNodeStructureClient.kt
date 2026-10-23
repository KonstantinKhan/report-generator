package dev.reportgenerator.domain

interface PdmNodeStructureClient {
    suspend fun loadTree(query: StructureQuery): PdmNode
    suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean = false
    ): PdmNode

    data object Empty : PdmNodeStructureClient {
        override suspend fun loadTree(query: StructureQuery): PdmNode {
            throw NotImplementedError()
        }

        override suspend fun loadNode(
            id: PdmNodeId,
            attributeKeys: Set<AttributeKey>,
            includeUnmapped: Boolean
        ): PdmNode {
            throw NotImplementedError()
        }
    }
}
