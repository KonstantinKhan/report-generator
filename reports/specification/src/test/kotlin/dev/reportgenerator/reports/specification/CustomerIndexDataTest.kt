package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.template.DataValue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

// «Индекс заказчика» корня: значение в данных отчётов, null когда атрибута нет, он пуст или из одних пробелов.
class CustomerIndexDataTest : ShouldSpec({

    fun root(index: AttributeValue?): PdmNode = PdmNode(
        id = PdmNodeId(1),
        kind = PdmNodeKind.Assembly,
        attributes = buildMap {
            put(AttributeKey.Designation, AttributeValue.Text("ИЗД.001.000"))
            put(AttributeKey.Name, AttributeValue.Text("Изделие"))
            if (index != null) put(AttributeKey.CustomerIndex, index)
        },
    )

    context("specification") {
        should("take the customer index of the root") {
            root(AttributeValue.Text("ИНД-7")).toSpecificationData().customerIndex shouldBe "ИНД-7"
        }

        should("trim the customer index") {
            root(AttributeValue.Text("  ИНД-7 ")).toSpecificationData().customerIndex shouldBe "ИНД-7"
        }

        should("be null when the attribute is not loaded") {
            root(null).toSpecificationData().customerIndex shouldBe null
        }

        should("be null when the attribute is empty") {
            root(AttributeValue.Empty).toSpecificationData().customerIndex shouldBe null
        }

        should("be null when the value is blank") {
            root(AttributeValue.Text("   ")).toSpecificationData().customerIndex shouldBe null
        }

        should("read the customer index of the root only, not of a child") {
            val child = PdmNode(
                id = PdmNodeId(2),
                kind = PdmNodeKind.Part,
                attributes = mapOf(
                    AttributeKey.Designation to AttributeValue.Text("ИЗД.002"),
                    AttributeKey.Name to AttributeValue.Text("Деталь"),
                    AttributeKey.CustomerIndex to AttributeValue.Text("ДЕТ-1"),
                ),
            )
            val link = CompositionUsage(target = child, position = Field.Missing, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            root(null).copy(usages = listOf(link)).toSpecificationData().customerIndex shouldBe null
        }
    }

    context("group specification") {
        should("take the customer index of the root") {
            root(AttributeValue.Text("ИНД-7")).toGroupSpecificationData().customerIndex shouldBe "ИНД-7"
        }

        should("trim the customer index") {
            root(AttributeValue.Text(" ИНД-7\t")).toGroupSpecificationData().customerIndex shouldBe "ИНД-7"
        }

        should("be null when the attribute is not loaded") {
            root(null).toGroupSpecificationData().customerIndex shouldBe null
        }

        should("be null when the attribute is empty") {
            root(AttributeValue.Empty).toGroupSpecificationData().customerIndex shouldBe null
        }

        should("be null when the value is blank") {
            root(AttributeValue.Text("")).toGroupSpecificationData().customerIndex shouldBe null
        }
    }

    should("keep the customer index in the data context as an optional field") {
        root(AttributeValue.Text("ИНД-7")).toSpecificationData().toDataContext().get("doc.customerIndex") shouldBe
            DataValue.Str("ИНД-7")
        root(null).toGroupSpecificationData().toDataContext().get("doc.customerIndex") shouldBe null
    }
})
