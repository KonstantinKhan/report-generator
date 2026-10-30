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

    should("expand the composition and the variants one level only, the documents go deeper") {
        specificationQuery(PdmNodeId(1)).relationDepth shouldBe mapOf(Relation.Composition to 1, Relation.Variant to 1)
    }

    should("request composition, document, made-from and variant relations") {
        specificationQuery(PdmNodeId(1)).relations shouldBe
            setOf(Relation.Composition, Relation.Document, Relation.MadeFrom, Relation.Variant)
    }

    should("not request the variant link for a variant of the root: its composition only") {
        val query = specificationQuery(PdmNodeId(2), variants = false)

        query.relations shouldBe setOf(Relation.Composition, Relation.Document, Relation.MadeFrom)
        query.relationDepth shouldBe mapOf(Relation.Composition to 1)
    }

    should("request designation, product designation, name and format attributes") {
        specificationQuery(PdmNodeId(1)).attributes shouldBe
            setOf(AttributeKey.Designation, AttributeKey.ProductDesignation, AttributeKey.Name, AttributeKey.Format)
    }

    should("request the note, the zone and the application method of the composition link") {
        specificationQuery(PdmNodeId(1)).linkAttributes shouldBe
            setOf(LinkAttributeKey.Note, LinkAttributeKey.Zone, LinkAttributeKey.ApplicationMethod)
    }

    should("keep the given root id") {
        specificationQuery(PdmNodeId(42)).rootId shouldBe PdmNodeId(42)
    }

    should("read the replacement groups of the root only: depth 0") {
        val query = specificationQuery(PdmNodeId(1))

        query.replacements shouldBe true
        query.replacementDepth shouldBe 0
    }
})
