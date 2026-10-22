package khan366kos.loodsman.api.dsl

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

class PdmTreeBuilder : Builder<PdmNode> {
    var pdmClient: PdmNodeStructureClient = PdmNodeStructureClient.Empty
    var id: Int = -1
    var depth: Int = 1
    var relations: Set<Relation> = setOf(Relation.Composition)
    var attributeKeys: Set<AttributeKey> = emptySet()
    var linkAttributeKeys: Set<LinkAttributeKey> = emptySet()

    override suspend fun build(): PdmNode =
        pdmClient.loadStructure(
            StructureQuery(
                rootId = PdmNodeId(id),
                depth = depth,
                relations = relations,
                attributes = attributeKeys,
                linkAttributes = linkAttributeKeys,
            )
        )
}
