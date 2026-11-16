package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.template.DataValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

// The data of the specification list: the walk of the structure, the rows (one per object), their parents and totals,
// the sections of the rows (the numbers of the lines are the engine's, see SpecificationListTest).
class SpecificationListDataTest : ShouldSpec({

    var nextId = 100

    fun specification(designation: String) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.Specification,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Спецификация $designation"),
        ),
    )

    // An operational documents list with its code («ВС» is the list of the specifications); null = no code.
    fun operationalList(code: String?) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.OperationalDocumentsList,
        attributes = buildMap {
            put(AttributeKey.Designation, AttributeValue.Empty)
            put(AttributeKey.Name, AttributeValue.Text("Ведомость"))
            if (code != null) put(AttributeKey.DocumentCode, AttributeValue.Text(code))
        },
    )

    // An object of the structure: the specification is attached when `spec` (its designation is the object's one).
    fun unit(
        kind: PdmNodeKind,
        designation: String,
        vararg usages: Usage,
        spec: Boolean = true,
        extra: List<Usage> = emptyList(),
    ) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = kind,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Узел $designation"),
        ),
        usages = buildList {
            if (spec) add(DocumentUsage(target = specification(designation)))
            addAll(extra)
            addAll(usages)
        },
    )

    fun part(designation: String) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.Part,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Деталь $designation"),
        ),
    )

    fun link(target: PdmNode, quantity: Double) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = Quantity.Exact(quantity),
        unit = Field.Missing,
    )

    // The root (the product): its own specification is `specDesignation` (the parents at the first level are that one).
    fun root(
        vararg usages: Usage,
        customerIndex: String? = null,
        designation: String = "ИЗД.001.000",
        specDesignation: String = "ИЗД.00",
    ) = PdmNode(
        id = PdmNodeId(1),
        kind = PdmNodeKind.Assembly,
        attributes = buildMap {
            put(AttributeKey.Designation, AttributeValue.Text(designation))
            put(AttributeKey.Name, AttributeValue.Text("Изделие"))
            if (customerIndex != null) put(AttributeKey.CustomerIndex, AttributeValue.Text(customerIndex))
        },
        usages = listOf(DocumentUsage(target = specification(specDesignation))) + usages.toList(),
    )

    fun rowOf(data: SpecificationListData, designation: String): SpecificationListRow =
        data.rows.single { it.designation == designation }

    should("skip a node with the list «ВС» together with its whole subtree") {
        val grandchild = unit(PdmNodeKind.Assembly, "СБ.03")
        val child = unit(PdmNodeKind.Assembly, "СБ.02", link(grandchild, 1.0))
        val skipped = unit(PdmNodeKind.Assembly, "СБ.01", link(child, 1.0), extra = listOf(DocumentUsage(target = operationalList("ВС"))))
        val kept = unit(PdmNodeKind.Assembly, "СБ.04")
        val data = root(link(skipped, 1.0), link(kept, 1.0)).toSpecificationListData()

        data.rows.map { it.designation } shouldContainExactly listOf("СБ.04")
    }

    should("keep a node whose operational documents list has another code, or no code") {
        val other = unit(PdmNodeKind.Assembly, "СБ.01", extra = listOf(DocumentUsage(target = operationalList("ВЭ"))))
        val noCode = unit(PdmNodeKind.Assembly, "СБ.02", extra = listOf(DocumentUsage(target = operationalList(null))))
        val data = root(link(other, 1.0), link(noCode, 1.0)).toSpecificationListData()

        data.rows.map { it.designation } shouldContainExactly listOf("СБ.01", "СБ.02")
    }

    should("list an object with several parents on one row, one parent line per link") {
        val shared = unit(PdmNodeKind.Assembly, "СБ.03")
        val first = unit(PdmNodeKind.Assembly, "СБ.01", link(shared, 2.0))
        val second = unit(PdmNodeKind.Assembly, "СБ.02", link(shared, 3.0))
        val data = root(link(first, 1.0), link(second, 1.0)).toSpecificationListData()

        data.rows.count { it.designation == "СБ.03" } shouldBe 1
        val row = rowOf(data, "СБ.03")
        row.parents.map { it.designation } shouldContainExactly listOf("СБ.01", "СБ.02")
        row.parents.map { it.quantity } shouldContainExactly listOf(2.0, 3.0)
    }

    should("make the total of an object the sum of the quantities of its parent links") {
        val shared = unit(PdmNodeKind.Assembly, "СБ.03")
        val parents = listOf("СБ.01", "СБ.02", "СБ.04").map { unit(PdmNodeKind.Assembly, it, link(shared, 1.5)) }
        val data = root(*parents.map { link(it, 1.0) }.toTypedArray()).toSpecificationListData()

        rowOf(data, "СБ.03").total!! shouldBe (4.5 plusOrMinus 1e-9)
    }

    should("take the root's specification as the parent of the objects at the first level") {
        val child = unit(PdmNodeKind.Assembly, "СБ.01")
        val data = root(link(child, 1.0), designation = "ИЗД.001.000", specDesignation = "ИЗД.00 СП")
            .toSpecificationListData()

        rowOf(data, "СБ.01").parents.map { it.designation } shouldContainExactly listOf("ИЗД.00 СП")
        data.documentDesignation shouldBe "ИЗД.001.000"
    }

    should("skip an object without a specification as a row, but walk its children") {
        val grandchild = unit(PdmNodeKind.Assembly, "СБ.02")
        val child = unit(PdmNodeKind.Assembly, "СБ.01", link(grandchild, 1.0))
        val noSpec = unit(PdmNodeKind.Assembly, "СБ.НС", link(child, 1.0), spec = false)
        val data = root(link(noSpec, 1.0)).toSpecificationListData()

        data.rows.map { it.designation } shouldContainExactly listOf("СБ.01", "СБ.02")
        // the object without a specification is not a parent in the cells: the child gets no parent from it
        rowOf(data, "СБ.01").parents.shouldBeEmpty()
        rowOf(data, "СБ.01").total shouldBe null
        rowOf(data, "СБ.02").parents.map { it.designation } shouldContainExactly listOf("СБ.01")
    }

    should("put the assemblies before the kits, each section sorted by designation") {
        val kitB = unit(PdmNodeKind.Kit, "КМ.02")
        val kitA = unit(PdmNodeKind.Kit, "КМ.01")
        val asmB = unit(PdmNodeKind.Assembly, "СБ.02")
        val asmA = unit(PdmNodeKind.Assembly, "СБ.01")
        val data = root(link(kitB, 1.0), link(asmB, 1.0), link(kitA, 1.0), link(asmA, 1.0)).toSpecificationListData()

        data.rows.map { it.section } shouldContainExactly listOf(
            SpecificationSection.ASSEMBLY, SpecificationSection.ASSEMBLY,
            SpecificationSection.KIT, SpecificationSection.KIT,
        )
        data.rows.filter { it.section == SpecificationSection.ASSEMBLY }.map { it.designation } shouldContainExactly listOf("СБ.01", "СБ.02")
        data.rows.filter { it.section == SpecificationSection.KIT }.map { it.designation } shouldContainExactly listOf("КМ.01", "КМ.02")
    }

    should("leave the parts out of the rows") {
        val data = root(link(part("АБ.01"), 1.0)).toSpecificationListData()

        data.rows.shouldBeEmpty()
    }

    should("give one record per row in the order of the sheet, without a number: the engine numbers the lines") {
        val rows = listOf(
            SpecificationListRow(SpecificationSection.ASSEMBLY, "СБ.01", "Узел 1", emptyList()),
            SpecificationListRow(SpecificationSection.ASSEMBLY, "СБ.02", "Узел 2", emptyList()),
            SpecificationListRow(SpecificationSection.KIT, "КМ.01", "Комплект", emptyList()),
        )
        val records = SpecificationListData(documentDesignation = "ИЗД.00 ВС", documentName = "Ведомость", rows = rows)
            .itemRecords()

        records.map { (it.fields["section"] as DataValue.Enum).name } shouldContainExactly listOf("ASSEMBLY", "ASSEMBLY", "KIT")
        records.map { (it.fields["designation"] as DataValue.Str).value } shouldContainExactly listOf("СБ.01", "СБ.02", "КМ.01")
        records.forEach { it.fields.containsKey("number") shouldBe false }
    }

    should("put the parents of a row into the record one line each, the quantities on the same lines") {
        val shared = unit(PdmNodeKind.Assembly, "СБ.03")
        val first = unit(PdmNodeKind.Assembly, "СБ.01", link(shared, 2.0))
        val second = unit(PdmNodeKind.Assembly, "СБ.02", link(shared, 3.0))
        val data = root(link(first, 1.0), link(second, 1.0)).toSpecificationListData()

        val record = data.itemRecords().single { (it.fields["designation"] as DataValue.Str).value == "СБ.03" }
        (record.fields["parentDesignation"] as DataValue.Str).value shouldBe "СБ.01\nСБ.02"
        (record.fields["parentQuantity"] as DataValue.Str).value shouldBe "2\n3"
        (record.fields["total"] as DataValue.Str).value shouldBe "5"
    }

    should("make an empty list for a product without any object") {
        val data = root().toSpecificationListData()

        data.rows.shouldBeEmpty()
        data.itemRecords().shouldBeEmpty()
    }

    should("take the customer index of the root, null when it is missing") {
        root(customerIndex = "ИНД-7").toSpecificationListData().customerIndex shouldBe "ИНД-7"
        root().toSpecificationListData().customerIndex shouldBe null
    }

    should("fail when the root has no specification") {
        val bare = PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ИЗД.001.000"),
                AttributeKey.Name to AttributeValue.Text("Изделие"),
            ),
        )
        shouldThrow<SpecificationDataException> { bare.toSpecificationListData() }
    }
})
