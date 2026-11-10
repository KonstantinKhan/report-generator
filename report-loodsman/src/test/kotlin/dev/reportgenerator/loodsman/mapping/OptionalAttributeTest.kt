package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.ObjectTypeInfo
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// Атрибут с optional: true может отсутствовать в метаданных Loodsman. Обязательный — по-прежнему ошибка.
class OptionalAttributeTest : ShouldSpec({

    val snapshot = MetadataSnapshot(
        linkTypes = emptyList(),
        objectTypes = listOf(ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Обозначение")),
        attributeNames = setOf("Обозначение", "Масса"),
        version = 1,
    )

    fun config(attributes: Map<String, AttributeRule>) = MappingConfig(
        kinds = mapOf("Деталь" to "PART"),
        relations = emptyList(),
        attributes = attributes,
    )

    should("parse the optional flag from yaml and default it to false") {
        val parsed = MappingConfigLoader.parse(
            """
            kinds:
              Деталь: PART
            relations: []
            attributes:
              customerIndex:
                name: Индекс заказчика
                optional: true
              mass:
                name: Масса
            """.trimIndent()
        )
        parsed.attributes.getValue("customerIndex").optional shouldBe true
        parsed.attributes.getValue("mass").optional shouldBe false
    }

    should("accept an optional attribute that is missing in Loodsman without errors") {
        val errors = MappingConfigValidator.validate(
            config(mapOf("customerIndex" to AttributeRule(name = "Индекс заказчика", optional = true))),
            snapshot,
        )
        errors.shouldBeEmpty()
    }

    should("accept an optional attribute by kind that is missing in Loodsman without errors") {
        val errors = MappingConfigValidator.validate(
            config(mapOf("customerIndex" to AttributeRule(byKind = mapOf("PART" to "Индекс заказчика"), optional = true))),
            snapshot,
        )
        errors.shouldBeEmpty()
    }

    should("accept an optional attribute that is present in Loodsman and warn about nothing") {
        val present = config(mapOf("mass" to AttributeRule(name = "Масса", optional = true)))
        MappingConfigValidator.validate(present, snapshot).shouldBeEmpty()
        MappingConfigValidator.optionalMissing(present, snapshot).shouldBeEmpty()
    }

    should("still report a required attribute that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                mapOf(
                    "customerIndex" to AttributeRule(name = "Индекс заказчика", optional = true),
                    "mass" to AttributeRule(name = "Вес"),
                )
            ),
            snapshot,
        )
        errors shouldContainExactly listOf("attributes.mass.name: attribute 'Вес' not found in Loodsman")
    }

    should("report a required byKind attribute that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(mapOf("mass" to AttributeRule(byKind = mapOf("PART" to "Вес")))),
            snapshot,
        )
        errors.single() shouldContain "attributes.mass.byKind.PART"
    }

    should("warn once per missing optional attribute name, without failing") {
        val warnings = MappingConfigValidator.optionalMissing(
            config(mapOf("customerIndex" to AttributeRule(name = "Индекс заказчика", optional = true))),
            snapshot,
        )
        warnings shouldContainExactly listOf(
            "attributes.customerIndex.name: optional attribute 'Индекс заказчика' not found in Loodsman, it is not requested",
        )
    }

    should("not warn about a required attribute that is missing") {
        MappingConfigValidator.optionalMissing(
            config(mapOf("mass" to AttributeRule(name = "Вес"))),
            snapshot,
        ).shouldBeEmpty()
    }

    should("validate the real yaml with customerIndex against the snapshot without customerIndex") {
        val real = MappingConfigLoader.load(emptyMap())
        real.attributes.getValue("customerIndex").optional shouldBe true
        MappingConfigValidator.validate(real, snapshot).none { it.contains("customerIndex") } shouldBe true
    }
})
