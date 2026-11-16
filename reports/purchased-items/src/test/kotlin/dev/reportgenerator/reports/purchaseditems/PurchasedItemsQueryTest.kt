package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.Relation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PurchasedItemsQueryTest {
    @Test
    fun `query requests explicit bounded composition and report fields`() {
        val query = purchasedItemsQuery(PdmNodeId(5693))
        assertEquals(32, query.depth)
        assertEquals(32, query.relationDepth[Relation.Composition])
        assertTrue(AttributeKey.SourceReceipt in query.attributes)
        assertTrue(AttributeKey.ProductCode in query.attributes)
        assertTrue(AttributeKey.Supplier in query.attributes)
        assertTrue(AttributeKey.AdjustmentQuantity in query.attributes)
        assertTrue(LinkAttributeKey.SpecificationSection in query.linkAttributes)
    }
}

