package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class FieldTest : ShouldSpec({
    context("Field.Value") {
        should("return value from orNull") {
            val field: Field<Int> = Field.Value(42)
            field.orNull() shouldBe 42
        }

        should("return value from orElse") {
            val field: Field<Int> = Field.Value(42)
            field.orElse(99) shouldBe 42
        }

        should("work with strings") {
            val field: Field<String> = Field.Value("hello")
            field.orNull() shouldBe "hello"
            field.orElse("default") shouldBe "hello"
        }
    }

    context("Field.Missing") {
        should("return null from orNull") {
            val field: Field<Int> = Field.Missing
            field.orNull() shouldBe null
        }

        should("return default from orElse") {
            val field: Field<Int> = Field.Missing
            field.orElse(99) shouldBe 99
        }
    }

    context("Field.NotLoaded") {
        should("return null from orNull") {
            val field: Field<Int> = Field.NotLoaded
            field.orNull() shouldBe null
        }

        should("return default from orElse") {
            val field: Field<Int> = Field.NotLoaded
            field.orElse(99) shouldBe 99
        }
    }
})
