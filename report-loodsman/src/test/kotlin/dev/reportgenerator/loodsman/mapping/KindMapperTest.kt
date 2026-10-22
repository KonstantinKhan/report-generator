package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import io.kotest.assertions.throwables.shouldThrow

class KindMapperTest : ShouldSpec({
    val config = MappingConfig(
        kinds = mapOf(
            "Деталь" to "PART",
            "Сборочная единица" to "ASSEMBLY",
            "Сборочный чертеж" to "ASSEMBLY_DRAWING"
        ),
        relations = mapOf(
            "Состоит из ..." to "COMPOSITION"
        ),
        requiredRelations = setOf("COMPOSITION"),
        onUnmapped = UnmappedPolicy(kind = OnUnmapped.KEEP, relation = OnUnmapped.SKIP)
    )
    val log = LoggerFactory.getLogger(this::class.java)

    context("map") {
        should("map known name") {
            val mapper = KindMapper(config, log)
            mapper.map("Деталь") shouldBe PdmNodeKind.Part
        }

        should("map known name ignoring case") {
            val mapper = KindMapper(config, log)
            mapper.map("деталь") shouldBe PdmNodeKind.Part
            mapper.map("ДЕТАЛЬ") shouldBe PdmNodeKind.Part
        }

        should("map known name ignoring whitespace") {
            val mapper = KindMapper(config, log)
            mapper.map("  Деталь  ") shouldBe PdmNodeKind.Part
        }

        should("map name with yo the same as name with ye") {
            val mapper = KindMapper(config, log)
            mapper.map("Сборочный чертеж") shouldBe PdmNodeKind.AssemblyDrawing
            mapper.map("Сборочный чертёж") shouldBe PdmNodeKind.AssemblyDrawing
            mapper.map("СБОРОЧНЫЙ ЧЕРТЁЖ") shouldBe PdmNodeKind.AssemblyDrawing
            mapper.map("Сборочный  чертёж") shouldBe PdmNodeKind.AssemblyDrawing
        }

        should("keep unknown name with yo unmapped and unchanged") {
            val mapper = KindMapper(config, log)
            mapper.map("Неизвестный чертёж") shouldBe PdmNodeKind.Unmapped("Неизвестный чертёж")
        }

        should("return Unmapped when KEEP policy and unknown kind") {
            val mapper = KindMapper(config, log)
            val result = mapper.map("Неизвестный тип")
            result shouldBe PdmNodeKind.Unmapped("Неизвестный тип")
        }

        should("return null when SKIP policy and unknown kind") {
            val skipConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(kind = OnUnmapped.SKIP)
            )
            val mapper = KindMapper(skipConfig, log)
            mapper.map("Неизвестный тип") shouldBe null
        }

        should("throw when FAIL policy and unknown kind") {
            val failConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(kind = OnUnmapped.FAIL)
            )
            val mapper = KindMapper(failConfig, log)
            shouldThrow<IllegalArgumentException> {
                mapper.map("Неизвестный тип")
            }
        }

        should("return null for null input") {
            val mapper = KindMapper(config, log)
            mapper.map(null) shouldBe null
        }
    }
})
