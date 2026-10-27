package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.Registries
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class AttributeRequestsTest : ShouldSpec({

    val mapping = MappingConfigLoader.load(emptyMap()).copy(
        attributes = mapOf(
            "mass" to AttributeRule(name = "Масса", byKind = mapOf("PART" to "Масса детали")),
            "designation" to AttributeRule(name = "Обозначение"),
        )
    )
    val requests = AttributeRequests(mapping)

    should("resolve the domain key of the key attribute") {
        requests.attributeRequest(PdmNodeKind.Part, "Обозначение", emptySet(), false).keyKey shouldBe
            AttributeKey.Designation
    }

    should("name requested attributes by kind and fall back to the common name") {
        requests.attributeRequest(PdmNodeKind.Part, null, setOf(AttributeKey.Mass), false).names shouldBe
            mapOf(AttributeKey.Mass to "Масса детали")
        requests.attributeRequest(PdmNodeKind.Assembly, null, setOf(AttributeKey.Mass), false).names shouldBe
            mapOf(AttributeKey.Mass to "Масса")
    }

    should("not resolve the key domain key when the key attribute differs from the rule name by case or whitespace") {
        requests.attributeRequest(PdmNodeKind.Part, "обозначение", emptySet(), false).keyKey shouldBe null
        requests.attributeRequest(PdmNodeKind.Part, " Обозначение ", emptySet(), false).keyKey shouldBe null
    }

    should("keep rule names as is without trimming them into mapped names") {
        val spaced = AttributeRequests(
            MappingConfigLoader.load(emptyMap()).copy(
                attributes = mapOf("designation" to AttributeRule(name = " Обозначение ")),
            ),
        )
        spaced.attributeRequest(PdmNodeKind.Part, null, setOf(AttributeKey.Designation), false).names shouldBe
            mapOf(AttributeKey.Designation to " Обозначение ")
        spaced.attributeRequest(PdmNodeKind.Part, null, emptySet(), false).mappedNames shouldBe setOf(" Обозначение ")
    }

    should("collect names of all rules of the kind as mapped") {
        requests.attributeRequest(PdmNodeKind.Part, null, emptySet(), false).mappedNames shouldBe
            setOf("Масса детали", "Обозначение")
    }

    should("name the product designation attribute only for standard and other products of the real mapping") {
        val real = AttributeRequests(MappingConfigLoader.load(emptyMap()))
        val keys = setOf(AttributeKey.ProductDesignation)

        real.attributeRequest(PdmNodeKind.Standard, "Обозначение", keys, false).names shouldBe
            mapOf(AttributeKey.ProductDesignation to "Обозначение изделия")
        real.attributeRequest(PdmNodeKind.Other, "Обозначение", keys, false).names shouldBe
            mapOf(AttributeKey.ProductDesignation to "Обозначение изделия")
        real.attributeRequest(PdmNodeKind.Part, "Обозначение", keys, false).names shouldBe
            mapOf(AttributeKey.ProductDesignation to null)
        real.attributeRequest(PdmNodeKind.Material, "Обозначение", keys, false).names shouldBe
            mapOf(AttributeKey.ProductDesignation to null)
    }

    should("read the object attributes for standard products only when the product designation is requested") {
        val real = AttributeRequests(MappingConfigLoader.load(emptyMap()))
        val key = setOf(AttributeKey.Designation)

        real.attributeRequest(PdmNodeKind.Standard, "Обозначение", key, false).needsObjectAttributes() shouldBe false
        real.attributeRequest(PdmNodeKind.Standard, "Обозначение", key + AttributeKey.ProductDesignation, false)
            .needsObjectAttributes() shouldBe true
        real.attributeRequest(PdmNodeKind.Part, "Обозначение", key + AttributeKey.ProductDesignation, false)
            .needsObjectAttributes() shouldBe false
    }

    should("resolve the product designation id of the mapping to its domain key") {
        Registries.attributeKeys.resolve("productDesignation") shouldBe AttributeKey.ProductDesignation
    }

    should("fail for a requested attribute without a rule in mapping") {
        val bare = AttributeRequests(MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap()))
        val error = shouldThrow<LoodsmanApiException> {
            bare.attributeRequest(PdmNodeKind.Part, null, setOf(AttributeKey.Mass), false)
        }
        error.message shouldBe "Attribute 'Mass' is not configured in mapping.attributes"
    }
})
