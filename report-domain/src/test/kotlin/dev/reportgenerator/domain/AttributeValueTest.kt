package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class AttributeValueTest : ShouldSpec({
    context("AttributeValue.Text") {
        should("return its text from orElse") {
            val value: AttributeValue? = AttributeValue.Text("ООО Ромашка")
            value.orElse("—") shouldBe "ООО Ромашка"
        }

        should("keep an empty text as a value") {
            val value: AttributeValue? = AttributeValue.Text("")
            value.orElse("—") shouldBe ""
        }
    }

    context("AttributeValue.Number") {
        should("return the number as a string from orElse") {
            val value: AttributeValue? = AttributeValue.Number(1.5)
            value.orElse("—") shouldBe "1.5"
        }
    }

    context("AttributeValue.Empty") {
        should("return the given default from orElse") {
            val value: AttributeValue? = AttributeValue.Empty
            value.orElse("—") shouldBe "—"
        }
    }

    context("missing map entry") {
        should("return the given default from orElse") {
            val attributes: Map<AttributeKey, AttributeValue> = emptyMap()
            attributes[AttributeKey.Mass].orElse("—") shouldBe "—"
        }
    }
})
