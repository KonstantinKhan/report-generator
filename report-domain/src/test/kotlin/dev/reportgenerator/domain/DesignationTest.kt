package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class DesignationTest : ShouldSpec({
    context("Designation.Value") {
        should("return text from orNull") {
            val designation = Designation.Value("АБВГ.123456.001")
            designation.orNull() shouldBe "АБВГ.123456.001"
        }

        should("work with empty string") {
            val designation = Designation.Value("")
            designation.orNull() shouldBe ""
        }
    }

    context("Designation.Missing") {
        should("return null from orNull") {
            val designation: Designation = Designation.Missing
            designation.orNull() shouldBe null
        }
    }

    context("Designation.NotLoaded") {
        should("return null from orNull") {
            val designation: Designation = Designation.NotLoaded
            designation.orNull() shouldBe null
        }
    }
})
