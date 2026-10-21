package dev.reportgenerator.domain

interface PdmNodeStructureClient {
    suspend fun loadStructure(query: StructureQuery): PdmNode
    suspend fun withProperties(id: PdmNodeId): PdmNode

    data object Empty : PdmNodeStructureClient {
        override suspend fun loadStructure(query: StructureQuery): PdmNode {
            throw NotImplementedError()
        }

        override suspend fun withProperties(id: PdmNodeId): PdmNode {
            throw NotImplementedError()
        }
    }
}
