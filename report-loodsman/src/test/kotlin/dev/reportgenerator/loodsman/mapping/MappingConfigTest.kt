package dev.reportgenerator.loodsman.mapping

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain

class MappingConfigTest : ShouldSpec({
    context("MappingConfig") {
        should("create and validate valid config") {
            val config = MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = listOf(RelationRule(link = "Состоит из ...", relation = "COMPOSITION")),
                requiredRelations = setOf("COMPOSITION"),
                attributes = mapOf(
                    "mass" to AttributeRule(name = "Масса")
                )
            )
            config.kinds.shouldContainKey("Деталь")
            config.kinds["Деталь"] shouldBe "PART"
            config.relations.single().link shouldBe "Состоит из ..."
        }

        should("validate valid config") {
            val config = MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = listOf(RelationRule(link = "Состоит из ...", relation = "COMPOSITION")),
                requiredRelations = setOf("COMPOSITION"),
                attributes = mapOf(
                    "mass" to AttributeRule(name = "Масса")
                )
            )
            config.validate()
        }

        should("throw error for unknown itemKind in config") {
            val invalidYaml = """
                kinds:
                  PRAT: PRAT
                relations:
                  - link: "Состоит из ..."
                    as: COMPOSITION
                requiredRelations: []
                attributes:
                  mass:
                    name: Масса
                onUnmapped:
                  kind: KEEP
                  relation: SKIP
            """.trimIndent()

            shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(invalidYaml)
            }
        }

        should("throw error for missing requiredRelations") {
            val invalidYaml = """
                kinds:
                  Деталь: PART
                relations:
                  - link: "Документы"
                    as: DOCUMENT
                requiredRelations:
                  - COMPOSITION
                attributes:
                  mass:
                    name: Масса
                onUnmapped:
                  kind: KEEP
                  relation: SKIP
            """.trimIndent()

            shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(invalidYaml)
            }.message shouldContain "Required relation 'COMPOSITION' not found in relations mapping"
        }

        should("load and validate default loodsman-mapping.yaml from classpath") {
            val config = MappingConfigLoader.load(emptyMap())
            config.kinds.shouldContainKey("Деталь")
            config.relations.map { it.link } shouldBe listOf("Состоит из ...", "Документы", "Технологическая ДСЕ для")
            config.requiredRelations.contains("COMPOSITION") shouldBe true
        }

        should("throw error for invalid itemKind in YAML") {
            val invalidYaml = """
                kinds:
                  PRAT: PRAT
                relations:
                  - link: "Состоит из ..."
                    as: COMPOSITION
                requiredRelations: []
                attributes:
                  mass:
                    name: Масса
                onUnmapped:
                  kind: KEEP
                  relation: SKIP
            """.trimIndent()

            shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(invalidYaml)
            }
        }

        should("throw error for AttributeRule without name and byKind") {
            val invalidYaml = """
                kinds:
                  Деталь: PART
                relations:
                  - link: "Состоит из ..."
                    as: COMPOSITION
                requiredRelations: []
                attributes:
                  mass: {}
                onUnmapped:
                  kind: KEEP
                  relation: SKIP
            """.trimIndent()

            shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(invalidYaml)
            }
        }
    }

    context("relation rules") {
        fun yamlWith(rules: String) = "kinds:\n  Деталь: PART\nrelations:\n" + rules.prependIndent("  ")

        should("parse direction and from from YAML") {
            val config = MappingConfigLoader.parse(
                yamlWith(
                    """
                      - link: "Технологическая ДСЕ для"
                        direction: INVERSE
                        from: [Деталь, Комплект]
                        as: TECHNOLOGICAL_PART
                    """.trimIndent()
                )
            )

            config.relations shouldBe listOf(
                RelationRule(
                    link = "Технологическая ДСЕ для",
                    relation = "TECHNOLOGICAL_PART",
                    direction = LinkDirection.INVERSE,
                    from = listOf("Деталь", "Комплект"),
                )
            )
        }

        should("use FORWARD direction and empty from by default") {
            val config = MappingConfigLoader.parse(
                yamlWith(
                    """
                      - link: "Документы"
                        as: DOCUMENT
                    """.trimIndent()
                )
            )

            config.relations.single().direction shouldBe LinkDirection.FORWARD
            config.relations.single().from shouldBe emptyList()
        }

        should("reject unknown relation id in as") {
            val error = shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(
                    yamlWith(
                        """
                          - link: "Документы"
                            as: NOPE
                        """.trimIndent()
                    )
                )
            }
            error.message shouldContain "Unknown relation 'NOPE'"
        }

        should("reject unknown direction in YAML") {
            shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(
                    yamlWith(
                        """
                          - link: "Документы"
                            direction: SIDEWAYS
                            as: DOCUMENT
                        """.trimIndent()
                    )
                )
            }
        }

        should("reject duplicate types in from") {
            val error = shouldThrow<IllegalArgumentException> {
                MappingConfigLoader.parse(
                    yamlWith(
                        """
                          - link: "Технологическая ДСЕ для"
                            direction: INVERSE
                            from: [Деталь, Деталь]
                            as: TECHNOLOGICAL_PART
                        """.trimIndent()
                    )
                )
            }
            error.message shouldContain "has duplicate types in from: Деталь, Деталь"
        }

        should("reject two rules with the same link and direction when from overlaps") {
            val error = shouldThrow<IllegalArgumentException> {
                MappingConfig(
                    kinds = mapOf("Деталь" to "PART"),
                    relations = listOf(
                        RelationRule(link = "Связь", relation = "DOCUMENT", from = listOf("Деталь", "Комплект")),
                        RelationRule(link = "Связь", relation = "TECHNOLOGICAL_PART", from = listOf("Комплект")),
                    ),
                ).validate()
            }
            error.message shouldContain "Relation rules at indices 0 and 1 overlap for link 'Связь' in direction FORWARD"
        }

        should("reject two rules with the same link and direction when one from is empty") {
            shouldThrow<IllegalArgumentException> {
                MappingConfig(
                    kinds = mapOf("Деталь" to "PART"),
                    relations = listOf(
                        RelationRule(link = "Связь", relation = "DOCUMENT"),
                        RelationRule(link = "Связь", relation = "TECHNOLOGICAL_PART", from = listOf("Деталь")),
                    ),
                ).validate()
            }
        }

        should("accept two rules with the same link when from does not overlap") {
            MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = listOf(
                    RelationRule(link = "Связь", relation = "DOCUMENT", from = listOf("Деталь")),
                    RelationRule(link = "Связь", relation = "TECHNOLOGICAL_PART", from = listOf("Комплект")),
                ),
            ).validate()
        }

        should("accept the same link with different directions") {
            MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = listOf(
                    RelationRule(link = "Связь", relation = "DOCUMENT"),
                    RelationRule(link = "Связь", relation = "TECHNOLOGICAL_PART", direction = LinkDirection.INVERSE),
                ),
            ).validate()
        }
    }
})
