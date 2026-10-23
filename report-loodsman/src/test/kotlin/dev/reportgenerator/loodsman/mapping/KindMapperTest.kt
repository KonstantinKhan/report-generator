package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        should("not map name that differs in case") {
            val mapper = KindMapper(config, log)
            mapper.map("деталь") shouldBe PdmNodeKind.Unmapped("деталь")
            mapper.map("ДЕТАЛЬ") shouldBe PdmNodeKind.Unmapped("ДЕТАЛЬ")
        }

        should("not map name with surrounding or inner whitespace and keep it unchanged") {
            val mapper = KindMapper(config, log)
            mapper.map("  Деталь  ") shouldBe PdmNodeKind.Unmapped("  Деталь  ")
            mapper.map("Сборочный  чертеж") shouldBe PdmNodeKind.Unmapped("Сборочный  чертеж")
        }

        should("not map name with ё when the key has е, and keep the name unchanged") {
            val mapper = KindMapper(config, log)
            mapper.map("Сборочный чертеж") shouldBe PdmNodeKind.AssemblyDrawing
            mapper.map("Сборочный чертёж") shouldBe PdmNodeKind.Unmapped("Сборочный чертёж")
            mapper.map("СБОРОЧНЫЙ ЧЕРТЁЖ") shouldBe PdmNodeKind.Unmapped("СБОРОЧНЫЙ ЧЕРТЁЖ")
        }

        should("keep unknown name with yo unmapped and unchanged") {
            val mapper = KindMapper(config, log)
            mapper.map("Неизвестный чертёж") shouldBe PdmNodeKind.Unmapped("Неизвестный чертёж")
        }

        should("throw with the exact name and the known keys when FAIL policy and name differs only by case") {
            val failConfig = config.copy(
                onUnmapped = config.onUnmapped.copy(kind = OnUnmapped.FAIL)
            )
            val mapper = KindMapper(failConfig, log)
            val error = shouldThrow<IllegalArgumentException> {
                mapper.map("деталь")
            }
            error.message shouldContain "Unknown item kind 'деталь'"
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
