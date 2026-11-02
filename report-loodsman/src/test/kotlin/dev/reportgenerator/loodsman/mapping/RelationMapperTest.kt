package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import io.kotest.assertions.throwables.shouldThrow

class RelationMapperTest : ShouldSpec({
    val config = MappingConfig(
        kinds = mapOf(
            "Деталь" to "PART"
        ),
        relations = listOf(
            RelationRule(link = "Состоит из ...", relation = "COMPOSITION"),
            RelationRule(link = "Документы", relation = "DOCUMENT"),
            RelationRule(
                link = "Технологическая ДСЕ для",
                relation = "TECHNOLOGICAL_PART",
                direction = LinkDirection.INVERSE,
                from = listOf("Деталь"),
            ),
        ),
        requiredRelations = setOf("COMPOSITION"),
        onUnmapped = UnmappedPolicy(kind = OnUnmapped.KEEP, relation = OnUnmapped.SKIP)
    )
    val log = LoggerFactory.getLogger(this::class.java)

    context("map") {
        should("map known relation") {
            val mapper = RelationMapper(config, log)
            mapper.map("Состоит из ...") shouldBe Relation.Composition
        }

        should("map the variant link to the variant relation") {
            val mapper = RelationMapper(config.copy(relations = config.relations + RelationRule(link = "Исполнения", relation = "VARIANT", from = listOf("Деталь"))), log)
            mapper.map("Исполнения") shouldBe Relation.Variant
        }

        should("map link name of an INVERSE rule to its relation") {
            val mapper = RelationMapper(config, log)
            mapper.map("Технологическая ДСЕ для") shouldBe Relation.TechnologicalPart
        }

        should("prefer FORWARD rule when the same link name has rules of both directions") {
            val both = config.copy(
                relations = listOf(
                    RelationRule(link = "Связь", relation = "TECHNOLOGICAL_PART", direction = LinkDirection.INVERSE),
                    RelationRule(link = "Связь", relation = "DOCUMENT"),
                ),
            )
            RelationMapper(both, log).map("Связь") shouldBe Relation.Document
        }

        should("not map relation that differs in case, ё or whitespace") {
            val mapper = RelationMapper(config, log)
            mapper.map("состоит из ...") shouldBe null
            mapper.map("  Состоит из ...  ") shouldBe null
            mapper.map("Состоит  из ...") shouldBe null
        }

        should("keep relation that differs in case, ё or whitespace as unmapped with the exact name") {
            val keepConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(relation = OnUnmapped.KEEP)
            )
            val mapper = RelationMapper(keepConfig, log)
            mapper.map("  Состоит из ...  ") shouldBe Relation.Unmapped("  Состоит из ...  ")
            mapper.map("Документы ") shouldBe Relation.Unmapped("Документы ")
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

        should("return link name of INVERSE rule for its relation") {
            val mapper = RelationMapper(config, log)
            mapper.pdmNameOf(Relation.TechnologicalPart) shouldBe "Технологическая ДСЕ для"
        }

        should("return pdm name for unmapped relation") {
            val mapper = RelationMapper(config, log)
            val unmapped = Relation.Unmapped("Custom Link")
            mapper.pdmNameOf(unmapped) shouldBe "Custom Link"
        }
    }

    context("rulesFor") {
        should("return rules whose relation is in the set with resolved relation and direction") {
            val mapper = RelationMapper(config, log)

            val rules = mapper.rulesFor(setOf(Relation.TechnologicalPart, Relation.Composition))

            rules shouldContainExactly listOf(
                ResolvedRelationRule(
                    link = "Состоит из ...",
                    direction = LinkDirection.FORWARD,
                    from = emptySet(),
                    relation = Relation.Composition,
                ),
                ResolvedRelationRule(
                    link = "Технологическая ДСЕ для",
                    direction = LinkDirection.INVERSE,
                    from = setOf("Деталь"),
                    relation = Relation.TechnologicalPart,
                ),
            )
        }

        should("return no rules for relations that are not configured") {
            RelationMapper(config, log).rulesFor(setOf(Relation.Unmapped("Custom"))) shouldBe emptyList()
        }
    }

    context("ResolvedRelationRule.appliesTo") {
        should("apply to any type when from is empty") {
            val rule = ResolvedRelationRule("Состоит из ...", LinkDirection.FORWARD, emptySet(), Relation.Composition)
            rule.appliesTo("Деталь") shouldBe true
            rule.appliesTo("Комплект") shouldBe true
        }

        should("apply only to listed types when from is set") {
            val rule = ResolvedRelationRule("Технологическая ДСЕ для", LinkDirection.INVERSE, setOf("Деталь"), Relation.TechnologicalPart)
            rule.appliesTo("Деталь") shouldBe true
            rule.appliesTo("Комплект") shouldBe false
        }
    }
})
