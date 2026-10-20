package dev.reportgenerator.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class DesignationTest : ShouldSpec({
    context("Designation.Value") {
        should("return its text from orElse") {
            val value: Designation = Designation.Value("АБВГ.123456.001")
            value.orElse("default") shouldBe "АБВГ.123456.001"
        }

        should("keep an empty string as a value") {
            val value: Designation = Designation.Value("")
            value.orElse("default") shouldBe ""
        }

        should("not trim the text") {
            val value: Designation = Designation.Value("  АБВГ.123456.002  ")
            value.orElse("default") shouldBe "  АБВГ.123456.002  "
        }
    }

    context("Designation.Missing") {
        should("return the given default from orElse") {
            val value: Designation = Designation.Missing
            value.orElse("—") shouldBe "—"
        }
    }

    context("Designation.NotLoaded") {
        should("throw from orElse") {
            val value: Designation = Designation.NotLoaded
            shouldThrow<IllegalStateException> { value.orElse("—") }
        }
    }
})
