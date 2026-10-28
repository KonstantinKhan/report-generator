package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class SpecificationQueryTest : ShouldSpec({
    should("load the documents of the root's children: depth 2") {
        specificationQuery(PdmNodeId(1)).depth shouldBe 2
    }

    should("expand the composition one level only, the documents go deeper") {
        specificationQuery(PdmNodeId(1)).relationDepth shouldBe mapOf(Relation.Composition to 1)
    }

    should("request composition, document and made-from relations only") {
        specificationQuery(PdmNodeId(1)).relations shouldBe setOf(Relation.Composition, Relation.Document, Relation.MadeFrom)
    }

    should("request designation, product designation, name and format attributes") {
        specificationQuery(PdmNodeId(1)).attributes shouldBe
            setOf(AttributeKey.Designation, AttributeKey.ProductDesignation, AttributeKey.Name, AttributeKey.Format)
    }

    should("request the note and the zone of the composition link") {
        specificationQuery(PdmNodeId(1)).linkAttributes shouldBe setOf(LinkAttributeKey.Note, LinkAttributeKey.Zone)
    }

    should("keep the given root id") {
        specificationQuery(PdmNodeId(42)).rootId shouldBe PdmNodeId(42)
    }
})
