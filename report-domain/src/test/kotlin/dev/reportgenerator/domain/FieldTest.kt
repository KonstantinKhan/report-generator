package dev.reportgenerator.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class FieldTest : ShouldSpec({
    context("Field.Value") {
        should("return the value from orElse") {
            val field: Field<Int> = Field.Value(42)
            field.orElse(99) shouldBe 42
        }

        should("work with strings") {
            val field: Field<String> = Field.Value("hello")
            field.orElse("default") shouldBe "hello"
        }
    }

    context("Field.Missing") {
        should("return the given default from orElse") {
            val field: Field<Int> = Field.Missing
            field.orElse(99) shouldBe 99
        }
    }

    context("Field.NotLoaded") {
        should("throw from orElse") {
            val field: Field<Int> = Field.NotLoaded
            shouldThrow<IllegalStateException> { field.orElse(99) }
        }
    }
})
