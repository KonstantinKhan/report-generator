package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

// Запрос атрибутов с optional-атрибутом: отсутствующий в метаданных не запрашивается и не валит запрос.
class OptionalAttributeRequestTest : ShouldSpec({

    val mapping = MappingConfigLoader.load(emptyMap()).copy(
        attributes = mapOf(
            "designation" to AttributeRule(name = "Обозначение"),
            "customerIndex" to AttributeRule(name = "Индекс заказчика", optional = true),
        ),
    )
    val requests = AttributeRequests(mapping)

    should("request the optional attribute when Loodsman has it") {
        requests.attributeRequest(
            PdmNodeKind.Assembly, null, setOf(AttributeKey.CustomerIndex), false,
            availableNames = setOf("Индекс заказчика"),
        ).names shouldContainExactly mapOf(AttributeKey.CustomerIndex to "Индекс заказчика")
    }

    should("skip the optional attribute that Loodsman does not have, without an error") {
        val request = requests.attributeRequest(
            PdmNodeKind.Assembly, null, setOf(AttributeKey.CustomerIndex), false,
            availableNames = setOf("Обозначение"),
        )
        request.names.shouldBeEmpty()
        request.needsObjectAttributes() shouldBe false
    }

    should("keep the required attributes when an optional one is skipped") {
        val request = requests.attributeRequest(
            PdmNodeKind.Assembly, null, setOf(AttributeKey.CustomerIndex, AttributeKey.Designation), false,
            availableNames = setOf("Обозначение"),
        )
        request.names shouldContainExactly mapOf(AttributeKey.Designation to "Обозначение")
    }

    should("request the optional attribute without filtering when no names are given") {
        requests.attributeRequest(PdmNodeKind.Assembly, null, setOf(AttributeKey.CustomerIndex), false).names shouldContainExactly
            mapOf(AttributeKey.CustomerIndex to "Индекс заказчика")
    }

    should("request the real specification attributes without customerIndex failing when Loodsman lacks it") {
        val real = AttributeRequests(MappingConfigLoader.load(emptyMap()))
        val names = setOf("Обозначение", "Наименование")
        real.attributeRequest(
            PdmNodeKind.Assembly, "Обозначение", setOf(AttributeKey.Designation, AttributeKey.CustomerIndex), false,
            availableNames = names,
        ).names shouldContainExactly mapOf(AttributeKey.Designation to "Обозначение")
    }
})
