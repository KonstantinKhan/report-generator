package dev.reportgenerator.loodsman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class NameNormalizationTest : ShouldSpec({

    should("lowercase, fold е and collapse whitespace") {
        normalizeForComparison("  Сборочная   ЕДИНИЦА\t") shouldBe "сборочная единица"
        normalizeForComparison("Ёлка") shouldBe "елка"
        normalizeForComparison("Ёлка") shouldBe normalizeForComparison("елка")
    }

    should("keep names without changes apart from normalization") {
        normalizeForComparison("Состоит из ...") shouldBe "состоит из ..."
    }
})
