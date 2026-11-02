package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LinkRuleSelectionTest : ShouldSpec({
    context("LinkAttributeRule.select") {
        should("return null when no rules match") {
            val rules = emptyList<LinkAttributeRule>()
            rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Part)
                .shouldBeNull()
        }

        should("select rule with no selectors") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", name = "Позиция")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Part)
            selected?.name shouldBe "Позиция"
        }

        should("prefer rule with child selector over no selector") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", name = "Generic"),
                LinkAttributeRule(relation = "COMPOSITION", child = setOf("PART"), name = "SpecificChild")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Part)
            selected?.name shouldBe "SpecificChild"
        }

        should("prefer rule with parent selector over no selector") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", name = "Generic"),
                LinkAttributeRule(relation = "COMPOSITION", parent = setOf("ASSEMBLY"), name = "SpecificParent")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Part)
            selected?.name shouldBe "SpecificParent"
        }

        should("prefer rule with both selectors") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", name = "Generic"),
                LinkAttributeRule(relation = "COMPOSITION", parent = setOf("ASSEMBLY"), name = "WithParent"),
                LinkAttributeRule(relation = "COMPOSITION", parent = setOf("ASSEMBLY"), child = setOf("PART"), name = "WithBoth")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Part)
            selected?.name shouldBe "WithBoth"
        }

        should("not match when parent kind doesn't match") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", parent = setOf("PART"), name = "OnlyForPart")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Material)
            selected.shouldBeNull()
        }

        should("not match when child kind doesn't match") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", child = setOf("PART"), name = "OnlyForPart")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Assembly, PdmNodeKind.Material)
            selected.shouldBeNull()
        }

        should("match when kinds match in selector sets") {
            val rules = listOf(
                LinkAttributeRule(
                    relation = "COMPOSITION",
                    parent = setOf("ASSEMBLY", "COMPLEX"),
                    child = setOf("PART", "MATERIAL"),
                    name = "MultiKind"
                )
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Complex, PdmNodeKind.Material)
            selected?.name shouldBe "MultiKind"
        }

        should("return null for Unmapped kinds when selector is not empty") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", child = setOf("PART"), name = "OnlyForPart")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Other, PdmNodeKind.Other)
            selected.shouldBeNull()
        }

        should("match Unmapped kinds with empty selector") {
            val rules = listOf(
                LinkAttributeRule(relation = "COMPOSITION", name = "ForAll")
            )
            val selected = rules.select(Relation.Composition, PdmNodeKind.Other, PdmNodeKind.Other)
            selected?.name shouldBe "ForAll"
        }
    }

    context("AttributeRule.nameFor") {
        should("return name for known kind when byKind is empty") {
            val rule = AttributeRule(name = "DefaultName")
            rule.nameFor(PdmNodeKind.Assembly) shouldBe "DefaultName"
        }

        should("return byKind override when present") {
            val rule = AttributeRule(
                name = "DefaultName",
                byKind = mapOf("STANDARD" to "StandardName")
            )
            rule.nameFor(PdmNodeKind.Standard) shouldBe "StandardName"
        }

        should("return default name when byKind doesn't have the kind") {
            val rule = AttributeRule(
                name = "DefaultName",
                byKind = mapOf("STANDARD" to "StandardName")
            )
            rule.nameFor(PdmNodeKind.Part) shouldBe "DefaultName"
        }

        should("return null when no name and byKind is empty") {
            val rule = AttributeRule()
            rule.nameFor(PdmNodeKind.Assembly).shouldBeNull()
        }

        should("return null when no name and kind not in byKind") {
            val rule = AttributeRule(
                byKind = mapOf("STANDARD" to "StandardName")
            )
            rule.nameFor(PdmNodeKind.Part).shouldBeNull()
        }

        should("return byKind value even when name is null") {
            val rule = AttributeRule(
                byKind = mapOf("STANDARD" to "StandardName")
            )
            rule.nameFor(PdmNodeKind.Standard) shouldBe "StandardName"
        }
    }
})
