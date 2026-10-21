package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class SpecificationQueryTest : ShouldSpec({
    should("load only the root's direct children") {
        specificationQuery(PdmNodeId(1)).depth shouldBe 1
    }

    should("request composition and document relations only") {
        specificationQuery(PdmNodeId(1)).relations shouldBe setOf(Relation.Composition, Relation.Document)
    }

    should("keep the given root id") {
        specificationQuery(PdmNodeId(42)).rootId shouldBe PdmNodeId(42)
    }
})
