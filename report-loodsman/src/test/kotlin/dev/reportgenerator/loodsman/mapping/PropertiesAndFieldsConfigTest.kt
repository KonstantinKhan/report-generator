package dev.reportgenerator.loodsman.mapping

import io.github.konstantinkhan.loodsman.PropField
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

private fun withFields(fields: LinkFieldsMapping): MappingConfig {
    val config = MappingConfigLoader.load(emptyMap())
    return config.copy(link = config.link.copy(fields = fields))
}

class PropertiesAndFieldsConfigTest : ShouldSpec({
    context("default mapping") {
        should("declare the version and state properties explicitly") {
            val config = MappingConfigLoader.load(emptyMap())

            config.properties shouldBe PropertiesMapping(version = PropField.VERSION, state = PropField.STATE)
        }

        should("declare the quantity and unit link fields explicitly") {
            val config = MappingConfigLoader.load(emptyMap())

            config.link.fields shouldBe LinkFieldsMapping(
                quantityMin = LinkField.MIN_QUANTITY,
                quantityMax = LinkField.MAX_QUANTITY,
                unit = LinkField.UNIT
            )
        }
    }

    context("validation") {
        should("reject a text field as the minimum quantity") {
            shouldThrow<IllegalArgumentException> {
                withFields(LinkFieldsMapping(quantityMin = LinkField.UNIT)).validate()
            }
        }

        should("reject a text field as the maximum quantity") {
            shouldThrow<IllegalArgumentException> {
                withFields(LinkFieldsMapping(quantityMax = LinkField.UNIT)).validate()
            }
        }

        should("reject a numeric field as the unit") {
            shouldThrow<IllegalArgumentException> {
                withFields(LinkFieldsMapping(unit = LinkField.MIN_QUANTITY)).validate()
            }
        }
    }

    context("loading") {
        should("fall back to the defaults when the blocks are absent") {
            val yaml = """
                kinds:
                  Деталь: PART
                relations:
                  Состоит из ...: COMPOSITION
                identity:
                  designation: { type: prop, field: PRODUCT }
                  name: { type: attribute, name: Наименование }
            """.trimIndent()

            val config = MappingConfigLoader.parse(yaml)

            config.properties shouldBe PropertiesMapping()
            config.link.fields shouldBe LinkFieldsMapping()
        }

        should("reject an unknown property field in YAML") {
            val yaml = """
                kinds:
                  Деталь: PART
                relations:
                  Состоит из ...: COMPOSITION
                identity:
                  designation: { type: prop, field: PRODUCT }
                  name: { type: attribute, name: Наименование }
                properties:
                  state: NOPE
            """.trimIndent()

            shouldThrow<IllegalArgumentException> { MappingConfigLoader.parse(yaml) }
        }

        should("reject an unknown link field in YAML") {
            val yaml = """
                kinds:
                  Деталь: PART
                relations:
                  Состоит из ...: COMPOSITION
                identity:
                  designation: { type: prop, field: PRODUCT }
                  name: { type: attribute, name: Наименование }
                link:
                  fields:
                    unit: NOPE
            """.trimIndent()

            shouldThrow<IllegalArgumentException> { MappingConfigLoader.parse(yaml) }
        }
    }
})
