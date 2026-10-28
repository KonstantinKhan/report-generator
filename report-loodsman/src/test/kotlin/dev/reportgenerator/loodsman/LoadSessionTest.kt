package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.StructureQuery
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger

private fun rootQuery(id: Int, depth: Int = 1, vararg attributes: AttributeKey) = StructureQuery(
    rootId = PdmNodeId(id),
    depth = depth,
    attributes = attributes.toSet(),
)

class LoadSessionTest : ShouldSpec({
    should("read a child shared by the root and the roots of loadTrees once inside one session") {
        val shared = Obj(4, "Деталь", "АБ.004", mapOf("Наименование" to "Общая"))
        val objects = listOf(
            Obj(1, "Сборочная единица", "АБ.001"),
            Obj(2, "Сборочная единица", "АБ.002"),
            Obj(3, "Сборочная единица", "АБ.003"),
            shared,
        )
        val fake = FakeLoodsman(objects, listOf(Edge(1, 2), Edge(1, 3), Edge(2, 4), Edge(3, 4)))
        val client = fake.client()

        client.loadSession {
            client.loadTree(rootQuery(1, depth = 2, AttributeKey.Name))
            client.loadTrees(listOf(rootQuery(2, depth = 2, AttributeKey.Name), rootQuery(3, depth = 2, AttributeKey.Name)))
        }

        fake.propsCalls.flatten().count { it == 4 } shouldBe 1
        fake.infoCalls.count { it == 4 } shouldBe 1
        fake.linkCalls.count { it == 2 } shouldBe 1
        fake.linkCalls.count { it == 3 } shouldBe 1
    }

    should("not keep the cache between calls outside a session") {
        val fake = FakeLoodsman(tree, treeEdges)
        val client = fake.client()

        client.loadTree(query(1))
        client.loadTree(query(1))

        fake.propsCalls.flatten().count { it == 2 } shouldBe 2
        fake.linkCalls.count { it == 1 } shouldBe 2
    }

    should("return trees in the order of the queries and keep the usages order whatever the order of responses") {
        val objects = listOf(
            Obj(10, "Сборочная единица", "АБ.010"),
            Obj(20, "Сборочная единица", "АБ.020"),
            Obj(30, "Сборочная единица", "АБ.030"),
            Obj(11, "Деталь", "АБ.011"),
            Obj(12, "Деталь", "АБ.012"),
            Obj(21, "Деталь", "АБ.021"),
            Obj(22, "Деталь", "АБ.022"),
            Obj(31, "Деталь", "АБ.031"),
        )
        val edges = listOf(Edge(10, 11), Edge(10, 12), Edge(20, 21), Edge(20, 22), Edge(30, 31))
        val gates = listOf(10, 20, 30).associateWith { CompletableDeferred<Unit>() }
        val fake = FakeLoodsman(objects, edges, linkGates = gates, signalInFlightAt = 3)
        val client = fake.client()

        coroutineScope {
            val job = async { client.loadTrees(listOf(rootQuery(10), rootQuery(20), rootQuery(30))) }
            fake.inFlightReached.await()
            // Ответы приходят в обратном порядке запросов.
            gates.getValue(30).complete(Unit)
            gates.getValue(20).complete(Unit)
            gates.getValue(10).complete(Unit)
            val trees = job.await()

            trees.map { it.id } shouldBe listOf(PdmNodeId(10), PdmNodeId(20), PdmNodeId(30))
            trees[0].usages.map { it.target.id } shouldBe listOf(PdmNodeId(11), PdmNodeId(12))
            trees[1].usages.map { it.target.id } shouldBe listOf(PdmNodeId(21), PdmNodeId(22))
            trees[2].usages.map { it.target.id } shouldBe listOf(PdmNodeId(31))
        }
    }

    should("return the same tree from loadTrees as from loadTree") {
        val fake = FakeLoodsman(tree, treeEdges)
        val client = fake.client()

        val single = client.loadTree(query(1, AttributeKey.Name))
        val batch = client.loadTrees(listOf(query(1, AttributeKey.Name))).single()

        batch shouldBe single
    }

    should("never exceed the concurrency limit shared by all trees of one load") {
        val roots = (1..6).map { Obj(it * 10, "Сборочная единица", "АБ.%03d".format(it * 10), mapOf("Наименование" to "Узел")) }
        val children = (1..6).map { Obj(it * 10 + 1, "Деталь", "АБ.%03d".format(it * 10 + 1), mapOf("Наименование" to "Д")) }
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(roots + children, children.map { Edge(it.id - 1, it.id) }, trackedGate = gate, signalInFlightAt = 2)
        val client = fake.client(concurrency = 2)

        coroutineScope {
            val job = async {
                client.loadTrees(roots.map { rootQuery(it.id, depth = 1, AttributeKey.Name) })
            }
            fake.inFlightReached.await()
            gate.complete(Unit)
            job.await().size shouldBe 6
        }

        fake.maxInFlight.get().shouldBeLessThanOrEqualTo(2)
    }

    should("cancel the other trees and rethrow the same error when one tree fails") {
        val objects = listOf(Obj(2, "Сборочная единица", "АБ.002"), Obj(3, "Сборочная единица", "АБ.003"))
        // Ответ на связи дерева 2 не придёт: дерево должно быть отменено ошибкой дерева 99, а не ждать ответа.
        val fake = FakeLoodsman(objects, emptyList(), linkGates = mapOf(2 to CompletableDeferred<Unit>()))
        val client = fake.client()

        val error = shouldThrow<LoodsmanApiException> {
            client.loadTrees(listOf(rootQuery(2), rootQuery(99), rootQuery(3)))
        }

        error.message shouldBe "Object not found in Loodsman: id=99"
    }

    should("send links of at most one hundred objects per request and place each row under its parent") {
        // Состав раскрывается у сборочных единиц: дети — сборочные, чтобы их связи запрашивались на втором уровне.
        val children = (2..151).map { Obj(it, "Сборочная единица", "АБ.%03d".format(it)) }
        val root = Obj(1, "Сборочная единица", "АБ.001")
        val grandchild = Obj(152, "Деталь", "АБ.152")
        val fake = FakeLoodsman(listOf(root, grandchild) + children, children.map { Edge(1, it.id) } + Edge(2, 152))

        val tree = fake.client().loadTree(rootQuery(1, depth = 2))

        fake.linkBatches.first() shouldBe listOf(1)
        fake.linkBatches.drop(1).map { it.size } shouldBe listOf(100, 50)
        tree.usages.map { it.target.id } shouldBe children.map { PdmNodeId(it.id) }
        tree.usages.first { it.target.id == PdmNodeId(3) }.target.usages shouldBe emptyList()
        tree.usages.first { it.target.id == PdmNodeId(2) }.target.usages.map { it.target.id } shouldBe listOf(PdmNodeId(152))
    }

    should("send one request for a link key that two concurrent loads ask for at once") {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeLoodsman(tree, treeEdges, trackedGate = gate, signalInFlightAt = 1)
        val client = fake.client()

        client.loadSession {
            coroutineScope {
                val first = async { client.loadTree(query(1)) }
                val second = async { client.loadTree(query(1)) }
                fake.inFlightReached.await()
                gate.complete(Unit)
                awaitAll(first, second)
            }
        }

        fake.linkCalls.count { it == 1 } shouldBe 1
    }

    should("keep a shared load alive when the caller that started it is cancelled") {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val memo = Memo<Int, String>(scope)
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val owner = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                memo.get(1) { calls.incrementAndGet(); gate.await(); "value" }
            }
            val waiter = scope.async { memo.get(1) { error("must not load twice") } }

            owner.cancelAndJoin()
            gate.complete(Unit)

            waiter.await() shouldBe "value"
            calls.get() shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    should("not keep a failed load in the cache") {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val memo = Memo<Int, String>(scope)

            shouldThrow<IllegalStateException> { memo.get(1) { error("boom") } }
            memo.get(1) { "ok" } shouldBe "ok"
        } finally {
            scope.cancel()
        }
    }
})
