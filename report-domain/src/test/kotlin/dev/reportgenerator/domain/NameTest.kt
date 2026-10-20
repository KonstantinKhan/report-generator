package dev.reportgenerator.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class NameTest : ShouldSpec({
    context("Name.Value") {
        should("return its text from orElse") {
            val value: Name = Name.Value("Болт")
            value.orElse("default") shouldBe "Болт"
        }

        should("keep an empty string as a value") {
            val value: Name = Name.Value("")
            value.orElse("default") shouldBe ""
        }

        should("not trim the text") {
            val value: Name = Name.Value("  Деталь  ")
            value.orElse("default") shouldBe "  Деталь  "
        }
    }

    context("Name.Missing") {
        should("return the given default from orElse") {
            val value: Name = Name.Missing
            value.orElse("—") shouldBe "—"
        }
    }

    context("Name.NotLoaded") {
        should("throw from orElse") {
            val value: Name = Name.NotLoaded
            shouldThrow<IllegalStateException> { value.orElse("—") }
        }
    }
})
