package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class StructureOpsTest : ShouldSpec({
    context("usagesOf") {
        should("filter usages by relation") {
            val part1 = PdmNode(
                id = PdmNodeId(1),
                kind = PdmNodeKind.Part,
            )
            val part2 = PdmNode(
                id = PdmNodeId(2),
                kind = PdmNodeKind.Part,
            )
            val assembly = PdmNode(
                id = PdmNodeId(3),
                kind = PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(part1, Field.Value(1), Quantity.Exact(1.0), Field.Value("шт")),
                    DocumentUsage(part2)
                )
            )

            val compositionUsages = assembly.usagesOf(Relation.Composition)
            compositionUsages.size shouldBe 1
            compositionUsages[0].child.id shouldBe PdmNodeId(1)

            val documentUsages = assembly.usagesOf(Relation.Document)
            documentUsages.size shouldBe 1
            documentUsages[0].child.id shouldBe PdmNodeId(2)
        }

        should("return empty list when no usages match") {
            val assembly = PdmNode(
                id = PdmNodeId(1),
                kind = PdmNodeKind.Assembly,
                usages = emptyList()
            )

            val usages = assembly.usagesOf(Relation.Composition)
            usages.size shouldBe 0
        }
    }

    context("walk") {
        should("traverse tree depth-first") {
            val leaf1 = PdmNode(PdmNodeId(4), PdmNodeKind.Part)
            val leaf2 = PdmNode(PdmNodeId(5), PdmNodeKind.Part)
            val parent = PdmNode(PdmNodeId(2), PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(leaf1, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(leaf2, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )
            val root = PdmNode(PdmNodeId(1), PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(parent, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )

            val walked = root.walk().map { it.child.id.asInt() }.toList()
            walked shouldBe listOf(2, 4, 5)
        }

        should("respect relation filter") {
            val part1 = PdmNode(PdmNodeId(2), PdmNodeKind.Part)
            val doc = PdmNode(PdmNodeId(3), PdmNodeKind.AssemblyDrawing)
            val assembly = PdmNode(PdmNodeId(1), PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(part1, Field.Missing, Quantity.Missing, Field.Missing),
                    DocumentUsage(doc)
                )
            )

            val compositionWalk = assembly.walk(Relation.Composition).map { it.child.id.asInt() }.toList()
            compositionWalk.size shouldBe 1
            compositionWalk[0] shouldBe 2

            val documentWalk = assembly.walk(Relation.Document).map { it.child.id.asInt() }.toList()
            documentWalk.size shouldBe 1
            documentWalk[0] shouldBe 3
        }
    }

    context("groupedByKind") {
        should("group usages by product kind") {
            val part = PdmNode(PdmNodeId(2), PdmNodeKind.Part)
            val std = PdmNode(PdmNodeId(3), PdmNodeKind.Standard)
            val mat = PdmNode(PdmNodeId(4), PdmNodeKind.Material)

            val assembly = PdmNode(PdmNodeId(1), PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(part, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(std, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(mat, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )

            val grouped = assembly.groupedByKind(Relation.Composition)
            grouped.size shouldBe 3
            grouped[PdmNodeKind.Part]?.size shouldBe 1
            grouped[PdmNodeKind.Standard]?.size shouldBe 1
            grouped[PdmNodeKind.Material]?.size shouldBe 1
        }

        should("respect relation filter in grouping") {
            val part = PdmNode(PdmNodeId(2), PdmNodeKind.Part)
            val doc = PdmNode(PdmNodeId(3), PdmNodeKind.AssemblyDrawing)

            val assembly = PdmNode(PdmNodeId(1), PdmNodeKind.Assembly,
                usages = listOf(
                    CompositionUsage(part, Field.Missing, Quantity.Missing, Field.Missing),
                    DocumentUsage(doc)
                )
            )

            val compositionGrouped = assembly.groupedByKind(Relation.Composition)
            compositionGrouped.size shouldBe 1
            compositionGrouped.containsKey(PdmNodeKind.Part) shouldBe true
            compositionGrouped.containsKey(PdmNodeKind.AssemblyDrawing) shouldBe false
        }
    }
})
