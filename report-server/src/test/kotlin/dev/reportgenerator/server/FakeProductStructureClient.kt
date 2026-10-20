package dev.reportgenerator.server

import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.ProductNode
import dev.reportgenerator.domain.ProductStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.StructureQuery

// Returns the same structure for every query and records the queries it got.
// If failure is set, loadStructure throws it instead.
// If structure is set, it is returned instead of the sample one.
class FakeProductStructureClient(
    private val failure: Throwable? = null,
    private val structure: ProductNode? = null,
) : ProductStructureClient {
    val queries = mutableListOf<StructureQuery>()

    override suspend fun loadStructure(query: StructureQuery): ProductNode {
        queries += query
        failure?.let { throw it }
        return structure ?: sampleStructure()
    }

    private fun sampleStructure() = ProductNode(
        id = ProductId("1"),
        kind = ProductKind.Assembly,
        designation = Designation.Value("ИЗД.001"),
        name = Name.Value("Изделие"),
        usages = listOf(
            CompositionUsage(
                child = ProductNode(ProductId("2"), ProductKind.Part, Designation.Value("АБ.002"), Name.Value("Вал")),
                position = Field.Missing,
                quantity = Quantity.Exact(2.0),
                unit = Field.Value("шт"),
            ),
            CompositionUsage(
                child = ProductNode(ProductId("3"), ProductKind.Assembly, Designation.Value("АБ.003"), Name.Value("Корпус")),
                position = Field.Missing,
                quantity = Quantity.Exact(1.0),
                unit = Field.Value("шт"),
            ),
            DocumentUsage(
                child = ProductNode(
                    ProductId("4"),
                    ProductKind.AssemblyDrawing,
                    Designation.Value("ИЗД.001 СБ"),
                    Name.Value("Изделие. Сборочный чертёж"),
                ),
            ),
        ),
    )
}
