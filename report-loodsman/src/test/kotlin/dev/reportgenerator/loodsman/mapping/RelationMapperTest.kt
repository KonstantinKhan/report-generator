package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import io.kotest.assertions.throwables.shouldThrow

class RelationMapperTest : ShouldSpec({
    val config = MappingConfig(
        kinds = mapOf(
            "Деталь" to "PART"
        ),
        relations = mapOf(
            "Состоит из ..." to "COMPOSITION",
            "Документы" to "DOCUMENT"
        ),
        requiredRelations = setOf("COMPOSITION"),
        identity = IdentityMapping(
            designation = FieldSource.Prop(PropField.PRODUCT),
            name = FieldSource.Attribute("Наименование")
        ),
        position = FieldSource.Attribute("Позиция"),
        onUnmapped = UnmappedPolicy(kind = OnUnmapped.KEEP, relation = OnUnmapped.SKIP)
    )
    val log = LoggerFactory.getLogger(this::class.java)

    context("map") {
        should("map known relation") {
            val mapper = RelationMapper(config, log)
            mapper.map("Состоит из ...") shouldBe Relation.Composition
        }

        should("map known relation ignoring case") {
            val mapper = RelationMapper(config, log)
            mapper.map("состоит из ...") shouldBe Relation.Composition
        }

        should("map known relation ignoring whitespace") {
            val mapper = RelationMapper(config, log)
            mapper.map("  Состоит из ...  ") shouldBe Relation.Composition
        }

        should("return Unmapped when KEEP policy and unknown relation") {
            val keepConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(relation = OnUnmapped.KEEP)
            )
            val mapper = RelationMapper(keepConfig, log)
            val result = mapper.map("Неизвестная связь")
            result shouldBe Relation.Unmapped("Неизвестная связь")
        }

        should("return null when SKIP policy and unknown relation") {
            val skipConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(relation = OnUnmapped.SKIP)
            )
            val mapper = RelationMapper(skipConfig, log)
            mapper.map("Неизвестная связь") shouldBe null
        }

        should("throw when FAIL policy and unknown relation") {
            val failConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(relation = OnUnmapped.FAIL)
            )
            val mapper = RelationMapper(failConfig, log)
            shouldThrow<IllegalArgumentException> {
                mapper.map("Неизвестная связь")
            }
        }

        should("return null for null input") {
            val mapper = RelationMapper(config, log)
            mapper.map(null) shouldBe null
        }
    }

    context("pdmNameOf") {
        should("return pdm name for known relation") {
            val mapper = RelationMapper(config, log)
            mapper.pdmNameOf(Relation.Composition) shouldBe "Состоит из ..."
        }

        should("return pdm name for unmapped relation") {
            val mapper = RelationMapper(config, log)
            val unmapped = Relation.Unmapped("Custom Link")
            mapper.pdmNameOf(unmapped) shouldBe "Custom Link"
        }
    }
})
