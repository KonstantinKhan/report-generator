package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.loodsman.LinkTypeInfo
import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.ObjectTypeInfo
import io.github.konstantinkhan.loodsman.PropField
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private val snapshot = MetadataSnapshot(
    linkTypes = listOf(
        LinkTypeInfo(id = 6, name = "Состоит из ..."),
        LinkTypeInfo(id = 1, name = "Документы"),
    ),
    objectTypes = listOf(
        ObjectTypeInfo(id = 1, typeName = "Деталь"),
        ObjectTypeInfo(id = 2, typeName = "Ёлка"),
    ),
    attributeNames = setOf("Наименование", "Обозначение изделия", "Масса", "Позиция"),
    version = 1,
)

private fun config(
    kinds: Map<String, String> = mapOf("Деталь" to "PART"),
    relations: Map<String, String> = mapOf("Состоит из ..." to "COMPOSITION"),
    requiredRelations: Set<String> = setOf("COMPOSITION"),
    identity: IdentityMapping = IdentityMapping(
        designation = FieldSource.Prop(PropField.PRODUCT),
        name = FieldSource.Attribute("Наименование"),
    ),
    attributes: Map<String, AttributeRule> = mapOf("mass" to AttributeRule(name = "Масса")),
    link: LinkMapping = LinkMapping(
        attributes = mapOf("position" to listOf(LinkAttributeRule(relation = "COMPOSITION", name = "Позиция"))),
    ),
) = MappingConfig(
    kinds = kinds,
    relations = relations,
    requiredRelations = requiredRelations,
    identity = identity,
    attributes = attributes,
    link = link,
)

class MappingConfigValidatorTest : ShouldSpec({

    should("return no errors for a config that matches the snapshot") {
        MappingConfigValidator.validate(config(), snapshot).shouldBeEmpty()
    }

    should("report a kind that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(config(kinds = mapOf("Неизвестная" to "PART")), snapshot)

        errors shouldContainExactly listOf("kinds: type 'Неизвестная' not found in Loodsman")
    }

    should("report a relation that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(relations = mapOf("Состоит из ..." to "COMPOSITION", "Нет такой" to "DOCUMENT")),
            snapshot,
        )

        errors shouldContainExactly listOf("relations: link type 'Нет такой' not found in Loodsman")
    }

    should("report a required relation without any matching link type and name the candidates") {
        val errors = MappingConfigValidator.validate(
            config(
                relations = mapOf("Состоит из ..." to "COMPOSITION", "Ссылки" to "DOCUMENT"),
                requiredRelations = setOf("DOCUMENT"),
            ),
            snapshot.copy(linkTypes = listOf(LinkTypeInfo(id = 6, name = "Состоит из ..."))),
        )

        errors shouldContainExactly listOf(
            "relations: link type 'Ссылки' not found in Loodsman",
            "requiredRelations: 'DOCUMENT' has no link type in Loodsman, tried: Ссылки",
        )
    }

    should("report an identity attribute that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                identity = IdentityMapping(
                    designation = FieldSource.Prop(PropField.PRODUCT),
                    name = FieldSource.Attribute("Наимен"),
                    byKind = mapOf(
                        "PART" to KindIdentity(designation = FieldSource.Attribute("Обозначение")),
                    ),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "identity.name: attribute 'Наимен' not found in Loodsman",
            "identity.byKind.PART.designation: attribute 'Обозначение' not found in Loodsman",
        )
    }

    should("report attributes name and byKind values that are missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                attributes = mapOf(
                    "mass" to AttributeRule(name = "Масса кг"),
                    "companyName" to AttributeRule(byKind = mapOf("PART" to "Наименование предприятия")),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "attributes.mass.name: attribute 'Масса кг' not found in Loodsman",
            "attributes.companyName.byKind.PART: attribute 'Наименование предприятия' not found in Loodsman",
        )
    }

    should("report link attribute that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                link = LinkMapping(
                    attributes = mapOf(
                        "position" to listOf(
                            LinkAttributeRule(relation = "COMPOSITION", name = "Позиция"),
                            LinkAttributeRule(relation = "COMPOSITION", parent = setOf("PART"), name = "Позиция 2"),
                        ),
                    ),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf("link.attributes.position[1].name: attribute 'Позиция 2' not found in Loodsman")
    }

    should("collect all errors at once") {
        val errors = MappingConfigValidator.validate(
            config(
                kinds = mapOf("Неизвестная" to "PART"),
                relations = mapOf("Состоит из ..." to "COMPOSITION", "Нет такой" to "DOCUMENT"),
                identity = IdentityMapping(
                    designation = FieldSource.Prop(PropField.PRODUCT),
                    name = FieldSource.Attribute("Наимен"),
                ),
                attributes = mapOf("mass" to AttributeRule(name = "Масса кг")),
                link = LinkMapping(
                    attributes = mapOf("note" to listOf(LinkAttributeRule(relation = "COMPOSITION", name = "Прим"))),
                ),
            ),
            snapshot,
        )

        errors shouldHaveSize 5
    }

    should("match kinds and relations ignoring case, ё and whitespace") {
        val errors = MappingConfigValidator.validate(
            config(
                kinds = mapOf("  ДЕТАЛЬ " to "PART", "елка" to "PART"),
                relations = mapOf("состоит  из ..." to "COMPOSITION"),
            ),
            snapshot,
        )

        errors.shouldBeEmpty()
    }

    should("match attributes strictly: different case is an error") {
        val errors = MappingConfigValidator.validate(
            config(
                identity = IdentityMapping(
                    designation = FieldSource.Prop(PropField.PRODUCT),
                    name = FieldSource.Attribute("наименование"),
                ),
                attributes = mapOf("mass" to AttributeRule(name = "масса")),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "identity.name: attribute 'наименование' not found in Loodsman",
            "attributes.mass.name: attribute 'масса' not found in Loodsman",
        )
    }

    should("validate the default loodsman-mapping.yaml against confirmed Loodsman metadata") {
        val mapping = MappingConfigLoader.load(emptyMap())
        val confirmed = MetadataSnapshot(
            linkTypes = listOf(
                LinkTypeInfo(id = 6, name = "Состоит из ..."),
                LinkTypeInfo(id = 1, name = "Документы"),
            ),
            objectTypes = listOf(
                "Деталь", "Сборочная единица", "Сборочный чертеж", "Комплекс",
                "Стандартное изделие", "Прочее изделие", "Материал по КД", "Комплект",
            ).mapIndexed { index, name -> ObjectTypeInfo(id = index + 1, typeName = name) },
            attributeNames = setOf(
                "Наименование", "Обозначение изделия", "Масса", "Наименование предприятия", "Позиция", "Примечание",
            ),
            version = 1,
        )

        MappingConfigValidator.validate(mapping, confirmed).shouldBeEmpty()
    }
})
