package dev.reportgenerator.domain

interface PdmNodeStructureClient {
    suspend fun loadStructure(query: StructureQuery): PdmNode
    suspend fun withProperties(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean = false
    ): PdmNode

    data object Empty : PdmNodeStructureClient {
        override suspend fun loadStructure(query: StructureQuery): PdmNode {
            throw NotImplementedError()
        }

        override suspend fun withProperties(
            id: PdmNodeId,
            attributeKeys: Set<AttributeKey>,
            includeUnmapped: Boolean
        ): PdmNode {
            throw NotImplementedError()
        }
    }
}
