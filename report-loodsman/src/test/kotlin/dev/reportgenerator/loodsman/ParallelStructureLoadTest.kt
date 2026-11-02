package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.VariantUsage
import dev.reportgenerator.reports.specification.loadSpecificationStructure
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

private const val MADE_FROM = "Изготавливается из ..."
private const val VARIANT = "Исполнения"
private const val DOCUMENT = "Документы"

/** Типы связей фейка для спецификации: состав, документы, материалы, исполнения (id = позиция + 1). */
private val specLinkNames = listOf(COMPOSITION, DOCUMENT, MADE_FROM, VARIANT)

/**
 * Изделие 1 с двумя исполнениями (2, 3). Общий узел 10 лежит у корня и у исполнения 2; 11 — только у корня;
 * 13 — только у исполнения 2; 12 — только у исполнения 3. Порядок рёбер задаёт порядок usages корня: 10, 11, 2, 3.
 */
private fun variantObjects() = listOf(
    Obj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
    Obj(2, "Сборочная единица", "АБ.001-02", mapOf("Наименование" to "Корпус 2")),
    Obj(3, "Сборочная единица", "АБ.001-03", mapOf("Наименование" to "Корпус 3")),
    Obj(10, "Деталь", "АБ.010", mapOf("Наименование" to "Общая")),
    Obj(11, "Деталь", "АБ.011", mapOf("Наименование" to "Корневая")),
    Obj(12, "Деталь", "АБ.012", mapOf("Наименование" to "Третья")),
    Obj(13, "Деталь", "АБ.013", mapOf("Наименование" to "Вторая")),
)

private fun variantEdges() = listOf(
    Edge(1, 10, min = 2.0, max = 2.0, unit = "шт"),
    Edge(1, 11, min = 1.0, max = 1.0, unit = "шт"),
    Edge(1, 2, link = VARIANT),
    Edge(1, 3, link = VARIANT),
    Edge(2, 10, min = 2.0, max = 2.0, unit = "шт"),
    Edge(2, 13, min = 1.0, max = 1.0, unit = "шт"),
    Edge(3, 12, min = 1.0, max = 1.0, unit = "шт"),
)

/** Клиент без переопределённой загрузки исполнений: дефолт интерфейса (корень, затем исполнения) — эталон. */
private class SequentialClient(private val delegate: PdmNodeStructureClient) : PdmNodeStructureClient {
    override suspend fun loadTree(query: StructureQuery): PdmNode = delegate.loadTree(query)
    override suspend fun loadTrees(queries: List<StructureQuery>): List<PdmNode> = delegate.loadTrees(queries)
    override suspend fun loadNode(
        id: PdmNodeId,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean,
    ): PdmNode = delegate.loadNode(id, attributeKeys, includeUnmapped)
}

/** Эталонная спецификация: тот же запрос, но исполнения читаются последовательно после корня. */
private suspend fun PdmNodeStructureClient.sequentialSpecification(rootId: PdmNodeId): PdmNode =
    loadSession { SequentialClient(this).loadSpecificationStructure(rootId) }

class ParallelStructureLoadTest : ShouldSpec({
    should("return the specification structure equal to the sequential reading with variants in the same order") {
        val fake = FakeLoodsman(variantObjects(), variantEdges(), linkNames = specLinkNames)
        val client = fake.client()

        val parallel = client.loadSpecificationStructure(PdmNodeId(1))
        val sequential = client.sequentialSpecification(PdmNodeId(1))

        parallel shouldBe sequential
        parallel.usages.map { it.target.id } shouldBe listOf(PdmNodeId(10), PdmNodeId(11), PdmNodeId(2), PdmNodeId(3))
        val variants = parallel.usages.filterIsInstance<VariantUsage>().map { it.target }
        variants.map { it.usages.map { usage -> usage.target.id } } shouldBe listOf(
            listOf(PdmNodeId(10), PdmNodeId(13)),
            listOf(PdmNodeId(12)),
        )
    }

    should("start the variant trees while the walk of the root is still on level one") {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(variantObjects(), variantEdges(), linkNames = specLinkNames, linkGates = mapOf(10 to gate))
        val client = fake.client()

        coroutineScope {
            val job = async { client.loadSpecificationStructure(PdmNodeId(1)) }
            // Раскрытие уровня 1 корня начинается с объекта 10 и заблокировано; исполнение 2 уже раскрывает свой состав.
            withTimeout(5_000) { fake.awaitAnswer("link:2") }
            job.isCompleted shouldBe false
            gate.complete(Unit)
            job.await().usages.size shouldBe 4
        }

        // Общие узлы корня и исполнения читаются один раз.
        fake.propsCalls.flatten().count { it == 10 } shouldBe 1
        fake.infoCalls.count { it == 10 } shouldBe 1
        fake.linkCalls.count { it == 10 } shouldBe 1
        fake.propsCalls.flatten().count { it == 2 } shouldBe 1
    }

    should("read the link attributes of a level before the next level is walked") {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(noteObjects(), noteEdges(), linkGates = mapOf(10 to gate))
        val client = fake.client()

        coroutineScope {
            val job = async { client.loadTree(noteQuery()) }
            withTimeout(5_000) {
                fake.awaitAnswer("attr:100")
                fake.awaitAnswer("attr:101")
            }
            job.isCompleted shouldBe false
            gate.complete(Unit)
            val root = job.await()

            // Пачки: атрибуты связей уровня 0, затем уровня 1 — две пачки против одной прежней.
            fake.linkAttributeBatches.map { it.sorted() } shouldBe listOf(listOf(100, 101), listOf(1000))
            fake.linkAttributeBatches.size shouldBeLessThanOrEqualTo 3

            val first = root.usages.first() as CompositionUsage
            first.attributes[LinkAttributeKey.Note] shouldBe AttributeValue.Text("первая")
            val nested = first.target.usages.single() as CompositionUsage
            nested.attributes[LinkAttributeKey.Note] shouldBe AttributeValue.Text("третья")
        }
    }

    should("start the replacements of the root before the walk of its children is done") {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(noteObjects(), noteEdges(), linkGates = mapOf(10 to gate))
        val client = fake.client()
        val query = noteQuery().copy(replacements = true, replacementDepth = 0)

        coroutineScope {
            val job = async { client.loadTree(query) }
            withTimeout(5_000) { fake.awaitAnswer("replacement:1") }
            job.isCompleted shouldBe false
            gate.complete(Unit)
            job.await().id shouldBe PdmNodeId(1)
        }
        fake.replacementCalls shouldBe listOf(1)
    }

    should("fail the whole load with the same exception when a variant fails") {
        val fake = FakeLoodsman(variantObjects(), variantEdges(), linkNames = specLinkNames, propFailures = setOf(2))
        val client = fake.client()

        val parallel = shouldThrow<LoodsmanApiException> { client.loadSpecificationStructure(PdmNodeId(1)) }
        val sequential = shouldThrow<LoodsmanApiException> { client.sequentialSpecification(PdmNodeId(1)) }

        // Тот же тип, код и вид ошибки; URL пачки свойств может отличаться: исполнения читают свои свойства отдельно.
        for (error in listOf(parallel, sequential)) {
            error.message.orEmpty() shouldContain "Loodsman API error 400"
            error.message.orEmpty() shouldContain "get-prop-objects"
        }
    }

    should("fail the whole load with the same exception when the root fails") {
        val fake = FakeLoodsman(variantObjects(), variantEdges(), linkNames = specLinkNames, propFailures = setOf(1))
        val client = fake.client()

        val parallel = shouldThrow<LoodsmanApiException> { client.loadSpecificationStructure(PdmNodeId(1)) }
        val sequential = shouldThrow<LoodsmanApiException> { client.sequentialSpecification(PdmNodeId(1)) }

        parallel.message shouldBe sequential.message
    }

    should("keep the usages order whatever the order of the variant responses") {
        val gates = mapOf(10 to CompletableDeferred<Unit>(), 2 to CompletableDeferred(), 3 to CompletableDeferred())
        val fake = FakeLoodsman(variantObjects(), variantEdges(), linkNames = specLinkNames, linkGates = gates)
        val client = fake.client()

        coroutineScope {
            val job = async { client.loadSpecificationStructure(PdmNodeId(1)) }
            // Ответ исполнения 3 приходит раньше ответа исполнения 2, корень — последним.
            fake.awaitLinkArrival(3)
            fake.awaitLinkArrival(2)
            gates.getValue(3).complete(Unit)
            fake.awaitAnswer("link:3")
            gates.getValue(2).complete(Unit)
            gates.getValue(10).complete(Unit)
            val reversed = job.await()

            reversed shouldBe client.sequentialSpecification(PdmNodeId(1))
            reversed.usages.map { it.target.id } shouldBe listOf(PdmNodeId(10), PdmNodeId(11), PdmNodeId(2), PdmNodeId(3))
        }
    }

    should("never exceed the concurrency limit while the root and the variants are in flight together") {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(
            variantObjects(), variantEdges(), linkNames = specLinkNames,
            trackedGate = gate, signalInFlightAt = 2,
        )
        val client = fake.client(concurrency = 2)

        coroutineScope {
            val job = async { client.loadSpecificationStructure(PdmNodeId(1)) }
            fake.inFlightReached.await()
            gate.complete(Unit)
            job.await().usages.size shouldBe 4
        }
        fake.maxInFlight.get() shouldBeLessThanOrEqualTo 2
    }
})

/** Фикстура атрибутов связей: корень 1 → 10 (сборка) и 11; 10 → 20. Примечания на каждой связи состава. */
private fun noteObjects() = listOf(
    Obj(1, "Сборочная единица", "АБ.001"),
    Obj(10, "Сборочная единица", "АБ.010"),
    Obj(11, "Деталь", "АБ.011"),
    Obj(20, "Деталь", "АБ.020"),
)

private fun noteEdges() = listOf(
    Edge(1, 10, attrs = mapOf("Примечание" to "первая")),
    Edge(1, 11, attrs = mapOf("Примечание" to "вторая")),
    Edge(10, 20, attrs = mapOf("Примечание" to "третья")),
)

/** Глубина 3 по составу: связи уровней 0, 1 и 2 раскрываются; атрибут — примечание связи состава. */
private fun noteQuery() = StructureQuery(
    rootId = PdmNodeId(1),
    depth = 3,
    relations = setOf(Relation.Composition),
    attributes = setOf(AttributeKey.Name),
    linkAttributes = setOf(LinkAttributeKey.Note),
)
