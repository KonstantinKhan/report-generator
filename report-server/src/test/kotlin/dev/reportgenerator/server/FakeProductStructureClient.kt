package dev.reportgenerator.server

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.StructureQuery

// Returns the same structure for every query and records the queries it got.
// If failure is set, loadStructure throws it instead.
// If structure is set, it is returned instead of the sample one.
class FakeProductStructureClient(
    private val failure: Throwable? = null,
    private val structure: PdmNode? = null,
) : PdmNodeStructureClient {
    val queries = mutableListOf<StructureQuery>()

    override suspend fun loadStructure(query: StructureQuery): PdmNode {
        queries += query
        failure?.let { throw it }
        return structure ?: sampleStructure()
    }

    override suspend fun withProperties(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean,
    ): PdmNode {
        queries += StructureQuery(id, attributes = attributeKeys, includeUnmappedAttributes = includeUnmapped)
        return structure ?: sampleStructure()
    }

    private fun sampleStructure() = PdmNode(
        id = PdmNodeId(1),
        kind = PdmNodeKind.Assembly,
        designation = Designation.Value("ИЗД.001"),
        name = Name.Value("Изделие"),
        usages = listOf(
            CompositionUsage(
                child = PdmNode(PdmNodeId(2), PdmNodeKind.Part, Designation.Value("АБ.002"), Name.Value("Вал")),
                position = Field.Missing,
                quantity = Quantity.Exact(2.0),
                unit = Field.Value("шт"),
            ),
            CompositionUsage(
                child = PdmNode(PdmNodeId(3), PdmNodeKind.Assembly, Designation.Value("АБ.003"), Name.Value("Корпус")),
                position = Field.Missing,
                quantity = Quantity.Exact(1.0),
                unit = Field.Value("шт"),
            ),
            DocumentUsage(
                child = PdmNode(
                    PdmNodeId(4),
                    PdmNodeKind.AssemblyDrawing,
                    Designation.Value("ИЗД.001 СБ"),
                    Name.Value("Изделие. Сборочный чертёж"),
                ),
            ),
        ),
    )
}
