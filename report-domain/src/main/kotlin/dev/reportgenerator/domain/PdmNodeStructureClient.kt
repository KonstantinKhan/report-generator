package dev.reportgenerator.domain

interface PdmNodeStructureClient {
    suspend fun loadTree(query: StructureQuery): PdmNode

    /**
     * Деревья по нескольким запросам; результат в порядке [queries]. Реализация может грузить деревья параллельно
     * и разделять общие данные между ними.
     */
    suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode>

    /**
     * Общий контекст загрузки для вложенных вызовов [loadTree] и [loadTrees]: данные, прочитанные в одном вызове,
     * не читаются заново. Вне реализации с контекстом — просто выполняет [block].
     */
    suspend fun <T> loadSession(block: suspend () -> T): T = block()

    suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean = false
    ): PdmNode

    data object Empty : PdmNodeStructureClient {
        override suspend fun loadTree(query: StructureQuery): PdmNode {
            throw NotImplementedError()
        }

        override suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode> {
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
