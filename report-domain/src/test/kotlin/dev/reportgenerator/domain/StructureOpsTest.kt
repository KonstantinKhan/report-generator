package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class StructureOpsTest : ShouldSpec({
    context("usagesOf") {
        should("filter usages by relation") {
            val part1 = ProductNode(
                id = ProductId("1"),
                kind = ProductKind.Part,
                designation = Designation.Value("P1"),
                name = Name.Value("Part 1")
            )
            val part2 = ProductNode(
                id = ProductId("2"),
                kind = ProductKind.Part,
                designation = Designation.Value("P2"),
                name = Name.Value("Part 2")
            )
            val assembly = ProductNode(
                id = ProductId("3"),
                kind = ProductKind.Assembly,
                designation = Designation.Value("A1"),
                name = Name.Value("Assembly"),
                usages = listOf(
                    CompositionUsage(part1, Field.Value(1), Quantity.Exact(1.0), Field.Value("шт")),
                    DocumentUsage(part2)
                )
            )

            val compositionUsages = assembly.usagesOf(Relation.Composition)
            compositionUsages.size shouldBe 1
            compositionUsages[0].child.id shouldBe ProductId("1")

            val documentUsages = assembly.usagesOf(Relation.Document)
            documentUsages.size shouldBe 1
            documentUsages[0].child.id shouldBe ProductId("2")
        }

        should("return empty list when no usages match") {
            val assembly = ProductNode(
                id = ProductId("1"),
                kind = ProductKind.Assembly,
                designation = Designation.Value("A1"),
                name = Name.Value("Assembly"),
                usages = emptyList()
            )

            val usages = assembly.usagesOf(Relation.Composition)
            usages.size shouldBe 0
        }
    }

    context("walk") {
        should("traverse tree depth-first") {
            val leaf1 = ProductNode(ProductId("4"), ProductKind.Part, Designation.Value("L1"), Name.Value("Leaf1"))
            val leaf2 = ProductNode(ProductId("5"), ProductKind.Part, Designation.Value("L2"), Name.Value("Leaf2"))
            val parent = ProductNode(ProductId("2"), ProductKind.Assembly, Designation.Value("P"), Name.Value("Parent"),
                usages = listOf(
                    CompositionUsage(leaf1, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(leaf2, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )
            val root = ProductNode(ProductId("1"), ProductKind.Assembly, Designation.Value("R"), Name.Value("Root"),
                usages = listOf(
                    CompositionUsage(parent, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )

            val walked = root.walk().map { it.child.id.value }.toList()
            walked shouldBe listOf("2", "4", "5")
        }

        should("respect relation filter") {
            val part1 = ProductNode(ProductId("2"), ProductKind.Part, Designation.Value("P1"), Name.Value("Part1"))
            val doc = ProductNode(ProductId("3"), ProductKind.AssemblyDrawing, Designation.Value("D"), Name.Value("Doc"))
            val assembly = ProductNode(ProductId("1"), ProductKind.Assembly, Designation.Value("A"), Name.Value("Asm"),
                usages = listOf(
                    CompositionUsage(part1, Field.Missing, Quantity.Missing, Field.Missing),
                    DocumentUsage(doc)
                )
            )

            val compositionWalk = assembly.walk(Relation.Composition).map { it.child.id.value }.toList()
            compositionWalk.size shouldBe 1
            compositionWalk[0] shouldBe "2"

            val documentWalk = assembly.walk(Relation.Document).map { it.child.id.value }.toList()
            documentWalk.size shouldBe 1
            documentWalk[0] shouldBe "3"
        }
    }

    context("groupedByKind") {
        should("group usages by product kind") {
            val part = ProductNode(ProductId("2"), ProductKind.Part, Designation.Value("P"), Name.Value("Part"))
            val std = ProductNode(ProductId("3"), ProductKind.Standard, Designation.Value("S"), Name.Value("Std"))
            val mat = ProductNode(ProductId("4"), ProductKind.Material, Designation.Value("M"), Name.Value("Mat"))

            val assembly = ProductNode(ProductId("1"), ProductKind.Assembly, Designation.Value("A"), Name.Value("Asm"),
                usages = listOf(
                    CompositionUsage(part, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(std, Field.Missing, Quantity.Missing, Field.Missing),
                    CompositionUsage(mat, Field.Missing, Quantity.Missing, Field.Missing)
                )
            )

            val grouped = assembly.groupedByKind(Relation.Composition)
            grouped.size shouldBe 3
            grouped[ProductKind.Part]?.size shouldBe 1
            grouped[ProductKind.Standard]?.size shouldBe 1
            grouped[ProductKind.Material]?.size shouldBe 1
        }

        should("respect relation filter in grouping") {
            val part = ProductNode(ProductId("2"), ProductKind.Part, Designation.Value("P"), Name.Value("Part"))
            val doc = ProductNode(ProductId("3"), ProductKind.AssemblyDrawing, Designation.Value("D"), Name.Value("Doc"))

            val assembly = ProductNode(ProductId("1"), ProductKind.Assembly, Designation.Value("A"), Name.Value("Asm"),
                usages = listOf(
                    CompositionUsage(part, Field.Missing, Quantity.Missing, Field.Missing),
                    DocumentUsage(doc)
                )
            )

            val compositionGrouped = assembly.groupedByKind(Relation.Composition)
            compositionGrouped.size shouldBe 1
            compositionGrouped.containsKey(ProductKind.Part) shouldBe true
            compositionGrouped.containsKey(ProductKind.AssemblyDrawing) shouldBe false
        }
    }
})
