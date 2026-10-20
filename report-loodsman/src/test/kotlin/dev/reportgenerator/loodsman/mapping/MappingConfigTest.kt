package dev.reportgenerator.loodsman.mapping

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.assertions.throwables.shouldThrow

class MappingConfigTest : ShouldSpec({
    context("MappingConfig") {
        should("create and validate valid config") {
            val config = MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = mapOf("Состоит из ..." to "COMPOSITION"),
                requiredRelations = setOf("COMPOSITION"),
                identity = IdentityMapping(
                    designation = FieldSource.Prop(PropField.PRODUCT),
                    name = FieldSource.Attribute("Наименование")
                ),
                attributes = mapOf(
                    "mass" to AttributeRule(name = "Масса")
                )
            )
            config.kinds.shouldContainKey("Деталь")
            config.kinds["Деталь"] shouldBe "PART"
            config.relations.shouldContainKey("Состоит из ...")
            config.identity.designation shouldBe FieldSource.Prop(PropField.PRODUCT)
        }

        should("validate valid config") {
            val config = MappingConfig(
                kinds = mapOf("Деталь" to "PART"),
                relations = mapOf("Состоит из ..." to "COMPOSITION"),
                requiredRelations = setOf("COMPOSITION"),
                identity = IdentityMapping(
                    designation = FieldSource.Prop(PropField.PRODUCT),
                    name = FieldSource.Attribute("Наименование")
                ),
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
                  Состоит из ...: COMPOSITION
                requiredRelations: []
                identity:
                  designation:
                    type: prop
                    field: PRODUCT
                  name:
                    type: attribute
                    name: Наименование
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
                  Документы: DOCUMENT
                requiredRelations:
                  - COMPOSITION
                identity:
                  designation:
                    type: prop
                    field: PRODUCT
                  name:
                    type: attribute
                    name: Наименование
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

        should("load and validate default loodsman-mapping.yaml from classpath") {
            val config = MappingConfigLoader.load(emptyMap())
            config.kinds.shouldContainKey("Деталь")
            config.relations.shouldContainKey("Состоит из ...")
            config.requiredRelations.contains("COMPOSITION") shouldBe true
        }

        should("throw error for invalid itemKind in YAML") {
            val invalidYaml = """
                kinds:
                  PRAT: PRAT
                relations:
                  Состоит из ...: COMPOSITION
                requiredRelations: []
                identity:
                  designation:
                    type: prop
                    field: PRODUCT
                  name:
                    type: attribute
                    name: Наименование
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
                  Состоит из ...: COMPOSITION
                requiredRelations: []
                identity:
                  designation:
                    type: prop
                    field: PRODUCT
                  name:
                    type: attribute
                    name: Наименование
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
})
