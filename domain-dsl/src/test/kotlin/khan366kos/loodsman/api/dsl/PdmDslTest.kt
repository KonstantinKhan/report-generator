package khan366kos.loodsman.api.dsl

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

// Records the calls the DSL makes and returns a fixed node.
private class RecordingClient : PdmNodeStructureClient {
    val queries = mutableListOf<StructureQuery>()
    val propertyCalls = mutableListOf<Triple<PdmNodeId, Set<AttributeKey>, Boolean>>()

    override suspend fun loadTree(query: StructureQuery): PdmNode {
        queries += query
        return node
    }

    override suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode> = queries.map { loadTree(it) }

    override suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean
    ): PdmNode {
        propertyCalls += Triple(id, attributeKeys, includeUnmapped)
        return node
    }

    private val node = PdmNode(
        PdmNodeId(1),
        PdmNodeKind.Assembly,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text("ИЗД.001"),
            AttributeKey.Name to AttributeValue.Text("Изделие"),
        ),
    )
}

class PdmDslTest : ShouldSpec({
    should("pdmTree pass root id, depth, relations and attribute keys to the structure query") {
        val client = RecordingClient()

        pdmTree {
            pdmClient = client
            id = 7
            depth = 3
            relations = setOf(Relation.Composition, Relation.Document)
            relationDepth = mapOf(Relation.Composition to 2)
            attributeKeys = setOf(AttributeKey.Mass)
            linkAttributeKeys = setOf(LinkAttributeKey.Note)
        }

        client.queries shouldBe listOf(
            StructureQuery(
                rootId = PdmNodeId(7),
                depth = 3,
                relations = setOf(Relation.Composition, Relation.Document),
                relationDepth = mapOf(Relation.Composition to 2),
                attributes = setOf(AttributeKey.Mass),
                linkAttributes = setOf(LinkAttributeKey.Note),
            )
        )
    }

    should("pdmTree use depth 1 and composition relation by default") {
        val client = RecordingClient()

        pdmTree {
            pdmClient = client
            id = 7
        }

        client.queries.single() shouldBe StructureQuery(
            rootId = PdmNodeId(7),
            depth = 1,
            relations = setOf(Relation.Composition),
        )
    }

    should("pdmTree return the node from the client") {
        val client = RecordingClient()

        val node = pdmTree {
            pdmClient = client
            id = 7
        }

        node.id shouldBe PdmNodeId(1)
        node.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Изделие")
    }

    should("pdmNode call loadNode with id, attribute keys and includeUnmapped") {
        val client = RecordingClient()

        pdmNode {
            pdmClient = client
            id = 5
            attributeKeys = setOf(AttributeKey.Mass, AttributeKey.CompanyName)
            includeUnmapped = true
        }

        client.propertyCalls shouldBe listOf(
            Triple(PdmNodeId(5), setOf(AttributeKey.Mass, AttributeKey.CompanyName), true)
        )
        client.queries shouldBe emptyList()
    }

    should("pdmNode call loadNode with empty attribute keys and includeUnmapped false by default") {
        val client = RecordingClient()

        pdmNode {
            pdmClient = client
            id = 5
        }

        client.propertyCalls shouldBe listOf(Triple(PdmNodeId(5), emptySet(), false))
    }
})
