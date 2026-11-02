package khan366kos.loodsman.api.dsl

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient

class PdmNodeBuilder : Builder<PdmNode> {
    var pdmClient: PdmNodeStructureClient = PdmNodeStructureClient.Empty
    var id: Int = -1
    var attributeKeys: Set<AttributeKey> = emptySet()
    var includeUnmapped: Boolean = false

    override suspend fun build(): PdmNode {
        val data = pdmClient.loadNode(PdmNodeId(id), attributeKeys, includeUnmapped)
        return data
    }
}