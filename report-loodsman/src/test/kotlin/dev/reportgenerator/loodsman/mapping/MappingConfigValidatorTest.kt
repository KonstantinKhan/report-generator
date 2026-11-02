package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.loodsman.LinkTypeInfo
import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.TypeLinkInfo
import dev.reportgenerator.loodsman.ObjectTypeInfo
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val snapshot = MetadataSnapshot(
    linkTypes = listOf(
        LinkTypeInfo(id = 6, name = "Состоит из ..."),
        LinkTypeInfo(id = 1, name = "Документы"),
    ),
    objectTypes = listOf(
        ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Обозначение"),
        ObjectTypeInfo(id = 2, typeName = "Ёлка", keyAttribute = "Обозначение"),
    ),
    attributeNames = setOf("Наименование", "Обозначение", "Масса", "Позиция"),
    version = 1,
)

private fun config(
    kinds: Map<String, String> = mapOf("Деталь" to "PART"),
    relations: List<RelationRule> = listOf(RelationRule(link = "Состоит из ...", relation = "COMPOSITION")),
    requiredRelations: Set<String> = setOf("COMPOSITION"),
    attributes: Map<String, AttributeRule> = mapOf("mass" to AttributeRule(name = "Масса")),
    link: LinkMapping = LinkMapping(
        attributes = mapOf("position" to listOf(LinkAttributeRule(relation = "COMPOSITION", name = "Позиция"))),
    ),
) = MappingConfig(
    kinds = kinds,
    relations = relations,
    requiredRelations = requiredRelations,
    attributes = attributes,
    link = link,
)

class MappingConfigValidatorTest : ShouldSpec({

    should("return no errors for a config that matches the snapshot") {
        MappingConfigValidator.validate(config(), snapshot).shouldBeEmpty()
    }

    should("report a kind that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(config(kinds = mapOf("Неизвестная" to "PART")), snapshot)

        errors shouldContainExactly listOf(
            "kinds: type 'Неизвестная' not found in Loodsman. Available: Ёлка, Деталь",
        )
    }

    should("list available Loodsman type names sorted in the kind error") {
        val errors = MappingConfigValidator.validate(config(kinds = mapOf("Деталь1" to "PART")), snapshot)

        errors.single() shouldContain "Available: Ёлка, Деталь"
    }

    should("report a relation that is missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                relations = listOf(
                    RelationRule(link = "Состоит из ...", relation = "COMPOSITION"),
                    RelationRule(link = "Нет такой", relation = "DOCUMENT"),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "relations: link type 'Нет такой' not found in Loodsman. Available: Документы, Состоит из ...",
        )
    }

    should("report a required relation without any matching link type and name the candidates") {
        val errors = MappingConfigValidator.validate(
            config(
                relations = listOf(
                    RelationRule(link = "Состоит из ...", relation = "COMPOSITION"),
                    RelationRule(link = "Ссылки", relation = "DOCUMENT"),
                ),
                requiredRelations = setOf("DOCUMENT"),
            ),
            snapshot.copy(linkTypes = listOf(LinkTypeInfo(id = 6, name = "Состоит из ..."))),
        )

        errors shouldContainExactly listOf(
            "relations: link type 'Ссылки' not found in Loodsman. Available: Состоит из ...",
            "requiredRelations: 'DOCUMENT' has no link type in Loodsman, tried: Ссылки. Available: Состоит из ...",
        )
    }

    should("report designation and name attributes that are missing in Loodsman") {
        val errors = MappingConfigValidator.validate(
            config(
                attributes = mapOf(
                    "designation" to AttributeRule(name = "Обозначение изделия"),
                    "name" to AttributeRule(name = "Наимен"),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "attributes.designation.name: attribute 'Обозначение изделия' not found in Loodsman",
            "attributes.name.name: attribute 'Наимен' not found in Loodsman",
        )
    }

    should("report a kind whose type has no key attribute in Loodsman") {
        val withoutKey = snapshot.copy(
            objectTypes = listOf(ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = null)),
        )

        val errors = MappingConfigValidator.validate(config(), withoutKey)

        errors shouldContainExactly listOf("kinds: type 'Деталь' has no key attribute in Loodsman")
    }

    should("report a key attribute of a kind that is missing among Loodsman attributes") {
        val withUnknownKey = snapshot.copy(
            objectTypes = listOf(ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Шифр")),
        )

        val errors = MappingConfigValidator.validate(config(), withUnknownKey)

        errors shouldContainExactly listOf(
            "kinds: key attribute 'Шифр' of type 'Деталь' not found among Loodsman attributes",
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
                relations = listOf(
                    RelationRule(link = "Состоит из ...", relation = "COMPOSITION"),
                    RelationRule(link = "Нет такой", relation = "DOCUMENT"),
                ),
                attributes = mapOf(
                    "name" to AttributeRule(name = "Наимен"),
                    "mass" to AttributeRule(name = "Масса кг"),
                ),
                link = LinkMapping(
                    attributes = mapOf("note" to listOf(LinkAttributeRule(relation = "COMPOSITION", name = "Прим"))),
                ),
            ),
            snapshot,
        )

        errors shouldHaveSize 5
    }

    should("do not match kinds and relations that differ in case, ё or whitespace") {
        val errors = MappingConfigValidator.validate(
            config(
                kinds = mapOf("  ДЕТАЛЬ " to "PART", "елка" to "PART"),
                relations = listOf(RelationRule(link = "состоит  из ...", relation = "COMPOSITION")),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "kinds: type '  ДЕТАЛЬ ' not found in Loodsman. Available: Ёлка, Деталь",
            "kinds: type 'елка' not found in Loodsman. Available: Ёлка, Деталь",
            "relations: link type 'состоит  из ...' not found in Loodsman. Available: Документы, Состоит из ...",
            "requiredRelations: 'COMPOSITION' has no link type in Loodsman, tried: состоит  из .... Available: Документы, Состоит из ...",
        )
    }

    should("match kind with ё only when the name contains ё exactly as in Loodsman") {
        MappingConfigValidator.validate(config(kinds = mapOf("Ёлка" to "PART")), snapshot).shouldBeEmpty()
        val errors = MappingConfigValidator.validate(config(kinds = mapOf("Елка" to "PART")), snapshot)

        errors.single() shouldContain "kinds: type 'Елка' not found in Loodsman"
    }

    should("match attributes strictly: different case is an error") {
        val errors = MappingConfigValidator.validate(
            config(
                attributes = mapOf(
                    "name" to AttributeRule(name = "наименование"),
                    "mass" to AttributeRule(name = "масса"),
                ),
            ),
            snapshot,
        )

        errors shouldContainExactly listOf(
            "attributes.name.name: attribute 'наименование' not found in Loodsman",
            "attributes.mass.name: attribute 'масса' not found in Loodsman",
        )
    }

    should("validate the default loodsman-mapping.yaml against confirmed Loodsman metadata") {
        val mapping = MappingConfigLoader.load(emptyMap())
        val confirmed = MetadataSnapshot(
            linkTypes = listOf(
                LinkTypeInfo(id = 6, name = "Состоит из ..."),
                LinkTypeInfo(id = 1, name = "Документы"),
                LinkTypeInfo(id = 7, name = "Технологическая ДСЕ для"),
            ),
            objectTypes = listOf(
                "Деталь", "Сборочная единица", "Сборочный чертеж", "Комплекс",
                "Стандартное изделие", "Прочее изделие", "Материал по КД", "Комплект",
                "Спецификация", "Чертеж детали",
            ).mapIndexed { index, name -> ObjectTypeInfo(id = index + 1, typeName = name, keyAttribute = "Обозначение") },
            attributeNames = setOf(
                "Наименование", "Обозначение", "Масса", "Наименование предприятия", "Позиция", "Примечание", "Формат",
            ),
            version = 1,
            typeLinks = listOf(TypeLinkInfo(typeId1 = 1, typeId2 = 2, idLinkType = 7, direction = -1)),
        )

        MappingConfigValidator.validate(mapping, confirmed).shouldBeEmpty()
    }

    context("variant link (Исполнения) rule") {
        val variantRule = RelationRule(link = "Исполнения", relation = "VARIANT", from = listOf("Сборочная единица"))
        val variantSnapshot = MetadataSnapshot(
            linkTypes = listOf(LinkTypeInfo(id = 9, name = "Исполнения")),
            objectTypes = listOf("Сборочная единица", "Деталь").mapIndexed { index, name ->
                ObjectTypeInfo(id = index + 1, typeName = name, keyAttribute = "Обозначение")
            },
            attributeNames = setOf("Обозначение"),
            version = 1,
        )
        fun withVariantLink(direction: Int) = variantSnapshot.copy(
            typeLinks = listOf(TypeLinkInfo(typeId1 = 1, typeId2 = 1, idLinkType = 9, direction = direction)),
        )

        should("accept the variant rule when the assembly unit allows the forward direction") {
            MappingConfigValidator.validate(
                config(relations = listOf(variantRule), requiredRelations = emptySet(), attributes = emptyMap(), link = LinkMapping()),
                withVariantLink(1),
            ).shouldBeEmpty()
        }

        should("report the variant rule when the assembly unit allows only the inverse direction") {
            val errors = MappingConfigValidator.validate(
                config(relations = listOf(variantRule), requiredRelations = emptySet(), attributes = emptyMap(), link = LinkMapping()),
                withVariantLink(-1),
            )
            errors.size shouldBe 1
        }
    }

    context("relation rules from and direction") {
        val techRule = RelationRule(
            link = "Технологическая ДСЕ для",
            relation = "TECHNOLOGICAL_PART",
            direction = LinkDirection.INVERSE,
            from = listOf("Деталь"),
        )
        fun withTechLink(vararg directions: Int) = snapshot.copy(
            linkTypes = snapshot.linkTypes + LinkTypeInfo(id = 7, name = "Технологическая ДСЕ для"),
            typeLinks = directions.map { TypeLinkInfo(typeId1 = 1, typeId2 = 2, idLinkType = 7, direction = it) },
        )

        should("accept INVERSE rule when the from type allows the inverse direction") {
            MappingConfigValidator.validate(
                config(relations = listOf(techRule), requiredRelations = emptySet()),
                withTechLink(-1),
            ).shouldBeEmpty()
        }

        should("accept rule when the from type allows both directions") {
            MappingConfigValidator.validate(
                config(relations = listOf(techRule), requiredRelations = emptySet()),
                withTechLink(0),
            ).shouldBeEmpty()
        }

        should("report INVERSE rule when the from type allows only the forward direction") {
            val errors = MappingConfigValidator.validate(
                config(relations = listOf(techRule), requiredRelations = emptySet()),
                withTechLink(1),
            )

            errors shouldContainExactly listOf(
                "правило 'Технологическая ДСЕ для': тип 'Деталь' не может быть источником связи в направлении INVERSE по метаданным Loodsman",
            )
        }

        should("report FORWARD rule when the from type allows only the inverse direction") {
            val errors = MappingConfigValidator.validate(
                config(
                    relations = listOf(RelationRule(link = "Технологическая ДСЕ для", relation = "TECHNOLOGICAL_PART", from = listOf("Деталь"))),
                    requiredRelations = emptySet(),
                ),
                withTechLink(-1),
            )

            errors shouldContainExactly listOf(
                "правило 'Технологическая ДСЕ для': тип 'Деталь' не может быть источником связи в направлении FORWARD по метаданным Loodsman",
            )
        }

        should("report from type without any link row in metadata") {
            val errors = MappingConfigValidator.validate(
                config(relations = listOf(techRule), requiredRelations = emptySet()),
                withTechLink(),
            )

            errors.single() shouldContain "тип 'Деталь' не может быть источником связи в направлении INVERSE"
        }

        should("report from type that is missing in Loodsman") {
            val errors = MappingConfigValidator.validate(
                config(
                    relations = listOf(techRule.copy(from = listOf("Неизвестная"))),
                    requiredRelations = emptySet(),
                ),
                withTechLink(-1),
            )

            errors shouldContainExactly listOf(
                "relations: from type 'Неизвестная' of link 'Технологическая ДСЕ для' not found in Loodsman. Available: Ёлка, Деталь",
            )
        }

        should("skip direction check when from is empty") {
            val rule = techRule.copy(from = emptyList())

            MappingConfigValidator.validate(
                config(relations = listOf(rule), requiredRelations = emptySet()),
                withTechLink(1),
            ).shouldBeEmpty()
        }

        should("skip direction check when link itself is missing in Loodsman") {
            val errors = MappingConfigValidator.validate(
                config(relations = listOf(techRule), requiredRelations = emptySet()),
                snapshot,
            )

            errors shouldContainExactly listOf(
                "relations: link type 'Технологическая ДСЕ для' not found in Loodsman. Available: Документы, Состоит из ...",
            )
        }
    }
})
