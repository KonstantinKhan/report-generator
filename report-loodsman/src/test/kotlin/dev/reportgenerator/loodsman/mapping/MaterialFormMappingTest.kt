package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.loodsman.LinkTypeInfo
import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.ObjectTypeInfo
import dev.reportgenerator.loodsman.TypeLinkInfo
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

private const val MADE_FROM_LINK = "Изготавливается из ..."
private const val MATERIAL_FORM_TYPE = "Форма материала"

// Метаданные: «Форма материала» (id 3) и «Материал по КД» (id 4); связь «Изготавливается из ...» (id 8) с направлением.
private fun metadata(direction: Int) = MetadataSnapshot(
    linkTypes = listOf(LinkTypeInfo(id = 8, name = MADE_FROM_LINK)),
    objectTypes = listOf(
        ObjectTypeInfo(id = 3, typeName = MATERIAL_FORM_TYPE, keyAttribute = "Обозначение"),
        ObjectTypeInfo(id = 4, typeName = "Материал по КД", keyAttribute = "Обозначение"),
    ),
    attributeNames = setOf("Обозначение"),
    version = 1,
    typeLinks = listOf(TypeLinkInfo(typeId1 = 3, typeId2 = 4, idLinkType = 8, direction = direction)),
)

private val madeFromRule = RelationRule(link = MADE_FROM_LINK, relation = "MADE_FROM", from = listOf(MATERIAL_FORM_TYPE))

// Конфиг только с правилом материала: валидатор сверяет все правила конфига, поэтому полный YAML здесь не подходит.
private val madeFromConfig = MappingConfig(
    kinds = mapOf(MATERIAL_FORM_TYPE to "MATERIAL_FORM"),
    relations = listOf(madeFromRule),
)

private val mapping = MappingConfigLoader.load(emptyMap())

class MaterialFormMappingTest : ShouldSpec({
    should("resolve the kind of a material form to MaterialForm") {
        val log = LoggerFactory.getLogger("test")

        KindMapper(mapping, log).map(MATERIAL_FORM_TYPE) shouldBe PdmNodeKind.MaterialForm
    }

    should("resolve the link 'Изготавливается из ...' to MadeFrom from a material form only") {
        val mapper = RelationMapper(mapping, LoggerFactory.getLogger("test"))

        mapper.map(MADE_FROM_LINK) shouldBe Relation.MadeFrom
        mapper.pdmNameOf(Relation.MadeFrom) shouldBe MADE_FROM_LINK

        val rule = mapper.rulesFor(setOf(Relation.MadeFrom)).single()
        rule.direction shouldBe LinkDirection.FORWARD
        rule.appliesTo(MATERIAL_FORM_TYPE) shouldBe true
        rule.appliesTo("Сборочная единица") shouldBe false
    }

    should("keep the composition rule of the assembly unchanged") {
        val mapper = RelationMapper(mapping, LoggerFactory.getLogger("test"))

        mapper.rulesFor(setOf(Relation.Composition)).single().appliesTo("Сборочная единица") shouldBe true
        mapper.rulesFor(setOf(Relation.Composition)).single().appliesTo(MATERIAL_FORM_TYPE) shouldBe false
    }

    should("accept the made-from rule of a material form when Loodsman allows the forward direction") {
        MappingConfigValidator.validate(madeFromConfig, metadata(direction = 1)).shouldBeEmpty()
    }

    should("report the made-from rule of a material form when Loodsman allows only the inverse direction") {
        MappingConfigValidator.validate(madeFromConfig, metadata(direction = -1)) shouldContainExactly listOf(
            "правило 'Изготавливается из ...': тип 'Форма материала' не может быть источником связи в направлении FORWARD по метаданным Loodsman",
        )
    }
})
