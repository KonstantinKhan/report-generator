package dev.reportgenerator.server

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.StructureQuery

// Returns the same structure for every query and records the queries it got.
// If failure is set, loadTree throws it instead.
// If structure is set, it is returned instead of the sample one.
class FakeProductStructureClient(
    private val failure: Throwable? = null,
    private val structure: PdmNode? = null,
) : PdmNodeStructureClient {
    val queries = mutableListOf<StructureQuery>()

    override suspend fun loadTree(query: StructureQuery): PdmNode {
        queries += query
        failure?.let { throw it }
        return structure ?: sampleStructure()
    }

    override suspend fun loadNode(
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
        attributes = attributesOf("ИЗД.001", "Изделие"),
        usages = listOf(
            CompositionUsage(
                target = PdmNode(
                    PdmNodeId(2),
                    PdmNodeKind.Part,
                    attributes = attributesOf("АБ.002", "Вал"),
                ),
                position = Field.Missing,
                quantity = Quantity.Exact(2.0),
                unit = Field.Value("шт"),
            ),
            CompositionUsage(
                target = PdmNode(
                    PdmNodeId(3),
                    PdmNodeKind.Assembly,
                    attributes = attributesOf("АБ.003", "Корпус"),
                ),
                position = Field.Missing,
                quantity = Quantity.Exact(1.0),
                unit = Field.Value("шт"),
            ),
            DocumentUsage(
                target = PdmNode(
                    PdmNodeId(4),
                    PdmNodeKind.AssemblyDrawing,
                    attributes = attributesOf("ИЗД.001 СБ", "Изделие. Сборочный чертёж"),
                ),
            ),
        ),
    )

    // Designation and name are read by the specification from attributes.
    private fun attributesOf(designation: String, name: String): Map<AttributeKey, AttributeValue> = mapOf(
        AttributeKey.Designation to AttributeValue.Text(designation),
        AttributeKey.Name to AttributeValue.Text(name),
    )
}
