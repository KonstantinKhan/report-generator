package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class NameTest : ShouldSpec({
    context("Name.Value") {
        should("return text from orNull") {
            val name = Name.Value("Болт")
            name.orNull() shouldBe "Болт"
        }

        should("work with empty string") {
            val name = Name.Value("")
            name.orNull() shouldBe ""
        }

        should("work with whitespace") {
            val name = Name.Value("  Деталь  ")
            name.orNull() shouldBe "  Деталь  "
        }
    }

    context("Name.Missing") {
        should("return null from orNull") {
            val name: Name = Name.Missing
            name.orNull() shouldBe null
        }
    }

    context("Name.NotLoaded") {
        should("return null from orNull") {
            val name: Name = Name.NotLoaded
            name.orNull() shouldBe null
        }
    }
})
