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

    /**
     * Дерево [rootQuery] с исполнениями: каждое [VariantUsage] корня получает полное дерево из [loadTree] по запросу
     * [variantQuery] (порядок usages сохраняется). Реализация может грузить корень и исполнения параллельно.
     * По умолчанию — последовательно: корень, затем исполнения.
     */
    suspend fun loadTreeWithVariants(
        rootQuery: StructureQuery,
        variantQuery: (PdmNodeId) -> StructureQuery,
    ): PdmNode {
        val root = loadTree(rootQuery)
        val variantIds = root.usages.filterIsInstance<VariantUsage>().map { it.target.id }
        if (variantIds.isEmpty()) return root
        val full = variantIds.zip(loadTrees(variantIds.map(variantQuery))).toMap()
        return root.copy(usages = root.usages.map { usage ->
            if (usage is VariantUsage) usage.copy(target = full.getValue(usage.target.id)) else usage
        })
    }

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
