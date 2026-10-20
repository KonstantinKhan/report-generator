package dev.reportgenerator.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class QuantityTest : ShouldSpec({
    context("Quantity.Exact") {
        should("return the amount as a string from orElse") {
            val quantity: Quantity = Quantity.Exact(1.0)
            quantity.orElse("—") shouldBe "1.0"
        }
    }

    context("Quantity.Range") {
        should("return min and max joined by a dash from orElse") {
            val quantity: Quantity = Quantity.Range(4.0, 6.0)
            quantity.orElse("—") shouldBe "4.0–6.0"
        }
    }

    context("Quantity.Missing") {
        should("return the given default from orElse") {
            val quantity: Quantity = Quantity.Missing
            quantity.orElse("—") shouldBe "—"
        }
    }

    context("Quantity.NotLoaded") {
        should("throw from orElse") {
            val quantity: Quantity = Quantity.NotLoaded
            shouldThrow<IllegalStateException> { quantity.orElse("—") }
        }
    }
})
