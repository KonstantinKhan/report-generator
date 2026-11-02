package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.VariantUsage
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

// The kits of the specification tree: a kit without the purpose and without a specification gets its composition by a
// second read (the composition of the kit is not in the query of the root). The fake client returns the prepared trees by
// the root id of the query and remembers the ids it was asked for.
class SpecificationStructureLoadTest : ShouldSpec({

    class FakeClient(private val trees: Map<Int, PdmNode>) : PdmNodeStructureClient {
        val requested = mutableListOf<Int>()

        override suspend fun loadTree(query: StructureQuery): PdmNode = loadTrees(listOf(query)).single()

        override suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode> = queries.map { query ->
            requested += query.rootId.asInt()
            trees.getValue(query.rootId.asInt())
        }

        override suspend fun loadNode(id: PdmNodeId, attributeKeys: Set<AttributeKey>, includeUnmapped: Boolean): PdmNode =
            throw NotImplementedError()
    }

    fun node(id: Int, kind: PdmNodeKind, usages: List<Usage> = emptyList()) = PdmNode(
        id = PdmNodeId(id),
        kind = kind,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text("АБ.$id"),
            AttributeKey.Name to AttributeValue.Text("Имя $id"),
        ),
        usages = usages,
    )

    fun link(child: PdmNode) = CompositionUsage(
        target = child,
        position = Field.Missing,
        quantity = Quantity.Exact(1.0),
        unit = Field.Missing,
    )

    fun root(vararg usages: Usage) = node(1, PdmNodeKind.Assembly, usages.toList())

    // The kit as the root of the tree has its composition: the part 5.
    val fullKit = node(2, PdmNodeKind.Kit, listOf(link(node(5, PdmNodeKind.Part))))

    should("read the composition of a kit without the purpose and without a specification into its usage") {
        val client = FakeClient(mapOf(1 to root(link(node(2, PdmNodeKind.Kit))), 2 to fullKit))

        val tree = client.loadSpecificationStructure(PdmNodeId(1))

        val kit = tree.usages.filterIsInstance<CompositionUsage>().single().target
        kit.usages.filterIsInstance<CompositionUsage>().single().target.id shouldBe PdmNodeId(5)
        client.requested shouldBe listOf(1, 2)
    }

    should("not read the composition of a kit with a specification") {
        val kit = node(2, PdmNodeKind.Kit, listOf(DocumentUsage(node(40, PdmNodeKind.Specification))))
        val client = FakeClient(mapOf(1 to root(link(kit)), 2 to fullKit))

        client.loadSpecificationStructure(PdmNodeId(1))

        client.requested shouldBe listOf(1)
    }

    should("read a kit of the variant and a kit used by the root and the variant once") {
        val variant = node(10, PdmNodeKind.Assembly, listOf(link(node(2, PdmNodeKind.Kit))))
        val client = FakeClient(
            mapOf(1 to root(link(node(2, PdmNodeKind.Kit)), VariantUsage(target = variant)), 10 to variant, 2 to fullKit),
        )

        val tree = client.loadSpecificationStructure(PdmNodeId(1))

        client.requested shouldBe listOf(1, 10, 2)
        val inVariant = tree.usages.filterIsInstance<VariantUsage>().single().target
        inVariant.usages.filterIsInstance<CompositionUsage>().single().target.usages.filterIsInstance<CompositionUsage>()
            .single().target.id shouldBe PdmNodeId(5)
    }
})
