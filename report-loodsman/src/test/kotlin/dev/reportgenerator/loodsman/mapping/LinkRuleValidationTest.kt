package dev.reportgenerator.loodsman.mapping

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

private fun rule(
    parent: Set<String> = emptySet(),
    child: Set<String> = emptySet(),
    name: String = "Позиция",
    relation: String = "COMPOSITION"
) = LinkAttributeRule(relation = relation, parent = parent, child = child, name = name)

private fun configWithPositionRules(vararg rules: LinkAttributeRule): MappingConfig =
    MappingConfigLoader.load(emptyMap()).copy(link = LinkMapping(attributes = mapOf("position" to rules.toList())))

class LinkRuleValidationTest : ShouldSpec({
    context("default mapping") {
        should("define a rule for every registered node attribute key") {
            val config = MappingConfigLoader.load(emptyMap())

            config.attributes.keys shouldContainAll Registries.attributeKeys.ids
        }

        should("define rules for every registered link attribute key") {
            val config = MappingConfigLoader.load(emptyMap())

            config.link.attributes.keys shouldContainAll Registries.linkAttributeKeys.ids
        }

        should("define a position rule for the composition relation") {
            val config = MappingConfigLoader.load(emptyMap())

            config.link.positionRules.any { it.relation == "COMPOSITION" } shouldBe true
        }
    }

    context("link rule validation") {
        should("accept rules of different shapes even if their sets look alike") {
            configWithPositionRules(
                rule(),
                rule(parent = setOf("ASSEMBLY")),
                rule(child = setOf("STANDARD")),
                rule(parent = setOf("ASSEMBLY"), child = setOf("STANDARD"))
            ).validate()
        }

        should("accept rules of the same shape with disjoint selectors") {
            configWithPositionRules(
                rule(child = setOf("PART")),
                rule(child = setOf("STANDARD"))
            ).validate()
        }

        should("accept the same selectors for different relations") {
            configWithPositionRules(
                rule(child = setOf("PART"), relation = "COMPOSITION"),
                rule(child = setOf("PART"), relation = "DOCUMENT")
            ).validate()
        }

        should("reject identical rules") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(rule(child = setOf("PART")), rule(child = setOf("PART"))).validate()
            }
        }

        should("reject two rules without selectors for the same relation") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(rule(), rule(name = "Другая позиция")).validate()
            }
        }

        should("reject overlapping selectors of the same shape") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(
                    rule(parent = setOf("ASSEMBLY")),
                    rule(parent = setOf("ASSEMBLY", "PART"), name = "Другая позиция")
                ).validate()
            }
        }

        should("reject an unknown relation") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(rule(relation = "UNKNOWN")).validate()
            }
        }

        should("reject an unknown parent kind") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(rule(parent = setOf("NOPE"))).validate()
            }
        }

        should("reject an unknown child kind") {
            shouldThrow<IllegalArgumentException> {
                configWithPositionRules(rule(child = setOf("NOPE"))).validate()
            }
        }

        should("reject rules for an unknown link attribute key") {
            val config = MappingConfigLoader.load(emptyMap())
                .copy(link = LinkMapping(attributes = mapOf("nope" to listOf(rule()))))

            shouldThrow<IllegalArgumentException> { config.validate() }
        }
    }
})
