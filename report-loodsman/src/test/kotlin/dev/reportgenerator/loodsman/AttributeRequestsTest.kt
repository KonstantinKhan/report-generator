package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
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

    should("collect names of all rules of the kind as mapped") {
        requests.attributeRequest(PdmNodeKind.Part, null, emptySet(), false).mappedNames shouldBe
            setOf("Масса детали", "Обозначение")
    }

    should("fail for a requested attribute without a rule in mapping") {
        val bare = AttributeRequests(MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap()))
        val error = shouldThrow<LoodsmanApiException> {
            bare.attributeRequest(PdmNodeKind.Part, null, setOf(AttributeKey.Mass), false)
        }
        error.message shouldBe "Attribute 'Mass' is not configured in mapping.attributes"
    }
})
