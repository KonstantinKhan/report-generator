package dev.reportgenerator.domain

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class NodeKeyTest : ShouldSpec({
    context("NodeKey.Value") {
        should("be equal for the same attribute and text") {
            NodeKey.Value(AttributeKey.Designation, "АБ.001") shouldBe NodeKey.Value(AttributeKey.Designation, "АБ.001")
        }

        should("differ when the text differs") {
            NodeKey.Value(AttributeKey.Designation, "АБ.001") shouldNotBe NodeKey.Value(AttributeKey.Designation, "АБ.002")
        }

        should("differ when the attribute differs") {
            NodeKey.Value(AttributeKey.Designation, "АБ.001") shouldNotBe NodeKey.Value(AttributeKey.Name, "АБ.001")
        }
    }

    context("NodeKey states") {
        should("keep Missing and NotLoaded as distinct states") {
            NodeKey.Missing shouldNotBe NodeKey.NotLoaded
        }

        should("not equal a value") {
            NodeKey.Missing shouldNotBe NodeKey.Value(AttributeKey.Designation, "")
            NodeKey.NotLoaded shouldNotBe NodeKey.Value(AttributeKey.Designation, "")
        }
    }

    context("PdmNode.key") {
        should("default to NotLoaded") {
            val node = PdmNode(PdmNodeId(1), PdmNodeKind.Part, Designation.Value("АБ.001"), Name.Value("Вал"))
            node.key shouldBe NodeKey.NotLoaded
        }
    }
})
