package dev.reportgenerator.domain

interface ProductStructureClient {
    suspend fun loadStructure(query: StructureQuery): ProductNode
}
