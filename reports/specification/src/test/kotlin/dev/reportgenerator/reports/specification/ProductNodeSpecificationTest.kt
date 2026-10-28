package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.MadeFromUsage
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.UnmappedUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// Designation, product designation and name are read from attributes; null means the key was not requested (NotLoaded).
private fun node(
    id: Int,
    kind: PdmNodeKind = PdmNodeKind.Part,
    designation: AttributeValue? = AttributeValue.Text("АБ.$id"),
    productDesignation: AttributeValue? = null,
    name: AttributeValue? = AttributeValue.Text("Имя $id"),
    usages: List<Usage> = emptyList(),
) = PdmNode(
    id = PdmNodeId(id),
    kind = kind,
    attributes = attributesOf(designation, productDesignation, name),
    usages = usages,
)

private fun attributesOf(
    designation: AttributeValue?,
    productDesignation: AttributeValue?,
    name: AttributeValue?,
): Map<AttributeKey, AttributeValue> = buildMap {
    designation?.let { put(AttributeKey.Designation, it) }
    productDesignation?.let { put(AttributeKey.ProductDesignation, it) }
    name?.let { put(AttributeKey.Name, it) }
}

private fun rootNode(
    designation: AttributeValue? = AttributeValue.Text("ИЗД.001"),
    name: AttributeValue? = AttributeValue.Text("Изделие"),
    usages: List<Usage> = emptyList(),
) = node(
    id = 1,
    kind = PdmNodeKind.Assembly,
    designation = designation,
    name = name,
    usages = usages,
)

private fun root(vararg usages: Usage) = rootNode(usages = usages.toList())

// The note is the link attribute; null: the note is not requested (not loaded).
private fun composition(
    child: PdmNode,
    quantity: Quantity = Quantity.Exact(1.0),
    unit: Field<String> = Field.Missing,
    position: Field<Int> = Field.Missing,
    note: AttributeValue? = null,
    zone: AttributeValue? = null,
) = CompositionUsage(
    target = child,
    position = position,
    quantity = quantity,
    unit = unit,
    attributes = buildMap {
        note?.let { put(LinkAttributeKey.Note, it) }
        zone?.let { put(LinkAttributeKey.Zone, it) }
    },
)

private fun document(child: PdmNode) = DocumentUsage(child)

private fun madeFrom(child: PdmNode) = MadeFromUsage(child)

// A material form with the materials it is made from (the made-from usages of the form).
private fun materialFormOf(id: Int, vararg materials: PdmNode) = node(
    id = id,
    kind = PdmNodeKind.MaterialForm,
    usages = materials.map { madeFrom(it) },
)

private fun unmapped(child: PdmNode) = UnmappedUsage(
    relation = Relation.Unmapped("Неведомая связь"),
    target = child,
)

// The item of the section of a standard or other product (the two share the shape, not the type).
private fun itemOf(kind: PdmNodeKind, designation: String?, name: String, quantity: Double): PositionedItem = when (kind) {
    PdmNodeKind.Standard -> StandardItem(designation = designation, name = name, quantity = quantity)
    PdmNodeKind.Other -> OtherItem(designation = designation, name = name, quantity = quantity)
    else -> error("no standard or other section for $kind")
}

private fun SpecificationData.onlyOf(kind: PdmNodeKind): PositionedItem = when (kind) {
    PdmNodeKind.Standard -> standards.single()
    PdmNodeKind.Other -> others.single()
    else -> error("no standard or other section for $kind")
}

class ProductNodeSpecificationTest : ShouldSpec({
    should("map the header from the root designation and name") {
        val data = root().toSpecificationData()

        data shouldBe SpecificationData("ИЗД.001", "Изделие")
    }

    should("use the root designation as document name when the name is missing") {
        val data = rootNode(name = AttributeValue.Empty).toSpecificationData()

        data.documentName shouldBe "ИЗД.001"
    }

    should("fail when the root designation is missing or not loaded") {
        val missing = rootNode(designation = AttributeValue.Empty)
        val notLoaded = rootNode(designation = null)

        shouldThrow<SpecificationDataException> { missing.toSpecificationData() }.message shouldContain "1"
        shouldThrow<SpecificationDataException> { notLoaded.toSpecificationData() }.message shouldContain "1"
    }

    should("fail when the root name is not loaded") {
        val node = rootNode(name = null)

        shouldThrow<SpecificationDataException> { node.toSpecificationData() }.message shouldContain "1"
    }

    should("route each product kind to its own section") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Assembly)),
            composition(node(3, kind = PdmNodeKind.Part)),
            composition(node(4, kind = PdmNodeKind.Standard)),
            composition(node(5, kind = PdmNodeKind.Other)),
            composition(node(6, kind = PdmNodeKind.Material)),
        ).toSpecificationData()

        data.assemblies.map { it.designation } shouldBe listOf("АБ.2")
        data.parts.map { it.designation } shouldBe listOf("АБ.3")
        data.standards.map { it.name } shouldBe listOf("АБ.4")
        data.others.map { it.name } shouldBe listOf("АБ.5")
        data.materials.map { it.name } shouldBe listOf("АБ.6")
    }

    should("skip complex and kit usages without failing") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Complex)),
            composition(node(3, kind = PdmNodeKind.Kit)),
            composition(node(4)),
        ).toSpecificationData()

        data shouldBe SpecificationData(
            "ИЗД.001",
            "Изделие",
            parts = listOf(PartItem(designation = "АБ.4", name = "Имя 4", quantity = 1.0)),
        )
    }

    should("drop specification and detail drawings from the composition and from the documents") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Specification)),
            composition(node(3, kind = PdmNodeKind.DetailDrawing)),
            document(node(4, kind = PdmNodeKind.Specification)),
        ).toSpecificationData()

        data shouldBe SpecificationData("ИЗД.001", "Изделие")
    }

    should("map exact quantity amount to the item") {
        val child = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(child, Quantity.Exact(2.5))).toSpecificationData()

        data.parts.single() shouldBe PartItem(designation = "АБ.2", name = "Имя 2", quantity = 2.5)
    }

    should("map the position of a composition usage to the item") {
        val child = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(child, position = Field.Value(7))).toSpecificationData()

        data.parts.single().position shouldBe 7
    }

    should("show a material designation in the name column and leave the designation empty") {
        val child = node(
            2,
            kind = PdmNodeKind.Material,
            designation = AttributeValue.Text("Сталь 09Г2С"),
            name = AttributeValue.Text("Лист"),
        )
        val data = root(composition(child, Quantity.Exact(2.5), Field.Value("кг"))).toSpecificationData()

        data.materials.single() shouldBe MaterialItem(designation = null, name = "Сталь 09Г2С", quantity = 2.5, notes = listOf("кг"))
    }

    should("fail on a material with a missing designation") {
        val child = node(2, kind = PdmNodeKind.Material, designation = AttributeValue.Empty)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a material with a not loaded designation") {
        val child = node(2, kind = PdmNodeKind.Material, designation = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    listOf(PdmNodeKind.Standard, PdmNodeKind.Other).forEach { productKind ->
        should("take the designation column from the product designation and the name column from the designation for a $productKind") {
            val child = node(
                2,
                kind = productKind,
                designation = AttributeValue.Text("ГОСТ 7798-70"),
                productDesignation = AttributeValue.Text("БОЛТ-1"),
                name = AttributeValue.Text("Болт"),
            )
            val data = root(composition(child, Quantity.Exact(2.0), Field.Value("шт"))).toSpecificationData()

            data.onlyOf(productKind) shouldBe itemOf(productKind, "БОЛТ-1", "ГОСТ 7798-70", 2.0)
        }

        should("leave the designation column empty for a $productKind when the product designation is missing") {
            val child = node(
                2,
                kind = productKind,
                designation = AttributeValue.Text("ГОСТ 7798-70"),
                productDesignation = AttributeValue.Empty,
            )
            val data = root(composition(child)).toSpecificationData()

            data.onlyOf(productKind) shouldBe itemOf(productKind, null, "ГОСТ 7798-70", 1.0)
        }

        should("leave the designation column empty for a $productKind when the product designation is not loaded") {
            val child = node(2, kind = productKind, designation = AttributeValue.Text("ГОСТ 7798-70"), productDesignation = null)
            val data = root(composition(child)).toSpecificationData()

            data.onlyOf(productKind) shouldBe itemOf(productKind, null, "ГОСТ 7798-70", 1.0)
        }

        should("fail on a $productKind when the designation is missing") {
            val child = node(2, kind = productKind, designation = AttributeValue.Empty, productDesignation = AttributeValue.Text("БОЛТ-1"))

            shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
        }

        should("fail on a $productKind with a not loaded designation") {
            val child = node(2, kind = productKind, designation = null, productDesignation = AttributeValue.Text("БОЛТ-1"))

            shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
        }

        should("not take the name attribute into account for a $productKind") {
            val child = node(2, kind = productKind, designation = AttributeValue.Text("ГОСТ 7798-70"), name = AttributeValue.Text("Болт"))
            val data = root(composition(child)).toSpecificationData()

            data.onlyOf(productKind) shouldBe itemOf(productKind, null, "ГОСТ 7798-70", 1.0)
        }
    }

    should("not read the product designation for a part") {
        val child = node(2, kind = PdmNodeKind.Part, productDesignation = AttributeValue.Text("ОБЗ-1"))
        val data = root(composition(child)).toSpecificationData()

        data.parts.single() shouldBe PartItem(designation = "АБ.2", name = "Имя 2", quantity = 1.0)
    }

    should("leave the notes of a material empty when its usage has no unit") {
        val data = root(composition(node(2, kind = PdmNodeKind.Material), Quantity.Exact(1.0), Field.Missing))
            .toSpecificationData()

        data.materials.single().notes shouldBe emptyList()
    }

    should("show the lower bound of a quantity range and the upper bound in the notes") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Part), Quantity.Range(1.0, 3.0), note = AttributeValue.Text("Закалить")),
            composition(
                node(3, kind = PdmNodeKind.Material),
                Quantity.Range(0.5, 1.25),
                unit = Field.Value("кг"),
                zone = AttributeValue.Text("1A,2B"),
            ),
        ).toSpecificationData()

        data.parts.single().quantity shouldBe 1.0
        data.parts.single().notes shouldBe listOf("Наиб. кол. 3", "Закалить")
        data.materials.single().quantity shouldBe 0.5
        data.materials.single().notes shouldBe listOf("кг", "*)1A,2B", "Наиб. кол. 1,25")
    }

    should("round the upper bound of a range to a whole count outside the materials") {
        val data = root(composition(node(2, kind = PdmNodeKind.Standard), Quantity.Range(2.0, 4.5))).toSpecificationData()

        data.standards.single().notes shouldBe listOf("Наиб. кол. 5")
    }

    should("give no maximum note for an exact quantity") {
        val data = root(composition(node(2, kind = PdmNodeKind.Part), Quantity.Exact(3.0))).toSpecificationData()

        data.parts.single().notes shouldBe emptyList()
    }

    should("fail on a missing or not loaded quantity") {
        val missing = composition(node(2), Quantity.Missing)
        val notLoaded = composition(node(3), Quantity.NotLoaded)

        shouldThrow<SpecificationDataException> { root(missing).toSpecificationData() }.message shouldContain "2"
        shouldThrow<SpecificationDataException> { root(notLoaded).toSpecificationData() }.message shouldContain "3"
    }

    should("fail on a not loaded usage unit") {
        val usage = composition(node(2), unit = Field.NotLoaded)

        shouldThrow<SpecificationDataException> { root(usage).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a not loaded usage position") {
        val usage = composition(node(2), position = Field.NotLoaded)

        shouldThrow<SpecificationDataException> { root(usage).toSpecificationData() }.message shouldContain "position"
    }

    should("map a missing usage position to null") {
        val data = root(composition(node(2))).toSpecificationData()

        data.parts.single().position shouldBe null
    }

    should("map a document usage to the documentation section without quantity") {
        val data = root(document(node(2, kind = PdmNodeKind.AssemblyDrawing))).toSpecificationData()

        data.documentation.single() shouldBe DocumentationItem("АБ.2", "Имя 2")
    }

    should("take the format of an assembly drawing and mark a complex one with a note") {
        val data = root(
            documentWith(PdmNodeKind.AssemblyDrawing, AttributeValue.Text("2хА0")),
            documentWith(PdmNodeKind.AssemblyDrawing, AttributeValue.Text("А1")),
        ).toSpecificationData()

        data.documentation.map { it.format to it.notes } shouldBe listOf(
            "*)" to listOf("*)2хА0"),
            "А1" to emptyList(),
        )
    }

    should("drop assembly drawings from the composition") {
        val drawing = node(2, kind = PdmNodeKind.AssemblyDrawing)
        val data = root(composition(drawing), composition(node(3))).toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.3")
        data.documentation shouldBe emptyList()
    }

    should("drop documents that are not assembly drawings") {
        val data = root(document(node(2, kind = PdmNodeKind.Part))).toSpecificationData()

        data.documentation shouldBe emptyList()
    }

    should("sort documentation by designation and by name when there is no designation") {
        val data = root(
            document(node(12, kind = PdmNodeKind.AssemblyDrawing, designation = AttributeValue.Text("АБ.20"))),
            document(node(13, kind = PdmNodeKind.AssemblyDrawing, designation = AttributeValue.Text("АБ.03"))),
            document(node(14, kind = PdmNodeKind.AssemblyDrawing, designation = AttributeValue.Empty, name = AttributeValue.Text("Аа"))),
        ).toSpecificationData()

        data.documentation shouldBe listOf(
            DocumentationItem("АБ.03", "Имя 13"),
            DocumentationItem("АБ.20", "Имя 12"),
            DocumentationItem(null, "Аа"),
        )
    }

    should("sort the items of a section by position and put the items without position last") {
        val data = root(
            composition(node(2), position = Field.Value(2)),
            composition(node(3), position = Field.Missing),
            composition(node(4), position = Field.Value(1)),
            composition(node(5, designation = AttributeValue.Empty, name = AttributeValue.Text("Аа")), position = Field.Missing),
            composition(node(6), position = Field.Missing),
        ).toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.4", "АБ.2", "АБ.3", "АБ.6", null)
    }

    should("keep the usage order of items with the same position") {
        val data = root(
            composition(node(8), position = Field.Value(1)),
            composition(node(7), position = Field.Value(1)),
        ).toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.8", "АБ.7")
    }

    should("skip usages whose kind is unmapped") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Unmapped("Неведомый тип"))),
            composition(node(3)),
        ).toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("skip unmapped usages") {
        val data = root(unmapped(node(2)), composition(node(3))).toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("map a missing designation to null") {
        val child = node(2, designation = AttributeValue.Empty)
        val data = root(composition(child)).toSpecificationData()

        data.parts.single().designation shouldBe null
    }

    should("fail on a not loaded designation") {
        val child = node(2, designation = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("use the designation as item name when the name is missing") {
        val child = node(2, designation = AttributeValue.Text("ГОСТ 7798-70"), name = AttributeValue.Empty)
        val data = root(composition(child)).toSpecificationData()

        data.parts.single().name shouldBe "ГОСТ 7798-70"
    }

    should("fail when the name and designation of an item are both missing") {
        val child = node(2, designation = AttributeValue.Empty, name = AttributeValue.Empty)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a not loaded item name") {
        val child = node(2, name = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("read a text attribute as a value") {
        val child = node(2, designation = AttributeValue.Text("АБ.7"), name = AttributeValue.Text("Вал"))
        val data = root(composition(child)).toSpecificationData()

        data.parts.single() shouldBe PartItem(designation = "АБ.7", name = "Вал", quantity = 1.0)
    }

    should("read an empty attribute as missing") {
        val child = node(2, designation = AttributeValue.Empty, name = AttributeValue.Text("Вал"))
        val data = root(composition(child)).toSpecificationData()

        data.parts.single() shouldBe PartItem(designation = null, name = "Вал", quantity = 1.0)
    }

    should("fail as not loaded when the attribute key was not requested") {
        val child = node(2, designation = null, name = AttributeValue.Text("Вал"))

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }
            .message shouldContain "designation is not loaded"
    }

    should("not treat a data error as an IllegalStateException so the generic handler does not catch it") {
        val exception: Throwable = SpecificationDataException("data")

        (exception is IllegalStateException) shouldBe false
    }

    should("take the format of an assembly from its first specification that has a format") {
        val assembly = node(2, kind = PdmNodeKind.Assembly)
        val data = root(
            composition(withDocuments(assembly, specificationOf(format = AttributeValue.Empty), specificationOf("A4"), specificationOf("A3"))),
        ).toSpecificationData()

        data.assemblies.single().format shouldBe "A4"
    }

    should("take the format of a part from its detail drawing, not from a specification") {
        val part = node(2, kind = PdmNodeKind.Part)
        val data = root(
            composition(withDocuments(part, specificationOf("A3"), detailDrawingOf("A4"))),
        ).toSpecificationData()

        data.parts.single().format shouldBe "A4"
    }

    should("give a simple format as is, without notes") {
        val part = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(withDocuments(part, detailDrawingOf("A4")))).toSpecificationData()

        data.parts.single() shouldBe PartItem(designation = "АБ.2", name = "Имя 2", quantity = 1.0, format = "A4")
    }

    should("mark a complex format with the mark and put its value into the first note") {
        val part = node(2, kind = PdmNodeKind.Part)
        val assembly = node(3, kind = PdmNodeKind.Assembly)
        val data = root(
            composition(withDocuments(part, detailDrawingOf("А3х3"))),
            composition(withDocuments(assembly, specificationOf("A3x3"))),
        ).toSpecificationData()

        data.parts.single() shouldBe PartItem(
            designation = "АБ.2", name = "Имя 2", quantity = 1.0, format = "*)", notes = listOf("*)А3х3"),
        )
        data.assemblies.single() shouldBe AssemblyItem(
            designation = "АБ.3", name = "Имя 3", quantity = 1.0, format = "*)", notes = listOf("*)A3x3"),
        )
    }

    should("treat the capital and the Latin x as a complex format too") {
        val part = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(withDocuments(part, detailDrawingOf("A3X3")))).toSpecificationData()

        data.parts.single().format shouldBe "*)"
        data.parts.single().notes shouldBe listOf("*)A3X3")
    }

    should("give no format when the item has no document of its kind") {
        val part = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(withDocuments(part, specificationOf("A4")))).toSpecificationData()

        data.parts.single().format shouldBe null
    }

    should("give no format when the document has no format or the format is not loaded") {
        val part = node(2, kind = PdmNodeKind.Part)
        val assembly = node(3, kind = PdmNodeKind.Assembly)
        val data = root(
            composition(withDocuments(part, detailDrawingOf(AttributeValue.Empty))),
            composition(withDocuments(assembly, specificationOf(format = null))),
        ).toSpecificationData()

        data.parts.single().format shouldBe null
        data.assemblies.single().format shouldBe null
    }

    should("put the note of the composition link into the notes of every section") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Assembly), note = AttributeValue.Text("Сварка")),
            composition(node(3, kind = PdmNodeKind.Part), note = AttributeValue.Text("Закалить")),
            composition(node(4, kind = PdmNodeKind.Standard), note = AttributeValue.Text("Окрасить")),
            composition(node(5, kind = PdmNodeKind.Other), note = AttributeValue.Text("Проверить")),
            composition(node(6, kind = PdmNodeKind.Material), note = AttributeValue.Text("Лист")),
        ).toSpecificationData()

        data.assemblies.single().notes shouldBe listOf("Сварка")
        data.parts.single().notes shouldBe listOf("Закалить")
        data.standards.single().notes shouldBe listOf("Окрасить")
        data.others.single().notes shouldBe listOf("Проверить")
        data.materials.single().notes shouldBe listOf("Лист")
    }

    should("take the zone of the composition link for every section") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Assembly), zone = AttributeValue.Text("А1")),
            composition(node(3, kind = PdmNodeKind.Part), zone = AttributeValue.Text("Б2")),
            composition(node(4, kind = PdmNodeKind.Standard), zone = AttributeValue.Text("В3")),
            composition(node(5, kind = PdmNodeKind.Other), zone = AttributeValue.Text("Г4")),
            composition(node(6, kind = PdmNodeKind.Material), zone = AttributeValue.Text("Д5")),
        ).toSpecificationData()

        data.assemblies.single().zone shouldBe "А1"
        data.parts.single().zone shouldBe "Б2"
        data.standards.single().zone shouldBe "В3"
        data.others.single().zone shouldBe "Г4"
        data.materials.single().zone shouldBe "Д5"
    }

    should("mark a complex zone with several zones and put its value into the notes") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Part), zone = AttributeValue.Text("7B, 8C")),
            composition(node(3, kind = PdmNodeKind.Material), zone = AttributeValue.Text("1A,2B"), note = AttributeValue.Text("Лист")),
        ).toSpecificationData()

        data.parts.single().zone shouldBe "*)"
        data.parts.single().notes shouldBe listOf("*)7B, 8C")
        data.materials.single().zone shouldBe "*)"
        data.materials.single().notes shouldBe listOf("*)1A,2B", "Лист")
    }

    should("put the complex format before the complex zone in the notes") {
        val data = root(
            composition(
                withDocuments(node(2, kind = PdmNodeKind.Part), detailDrawingOf("А3х3")),
                zone = AttributeValue.Text("7B,8C"),
                note = AttributeValue.Text("Закалить"),
            ),
        ).toSpecificationData()

        data.parts.single().format shouldBe "*)"
        data.parts.single().zone shouldBe "*)"
        data.parts.single().notes shouldBe listOf("*)А3х3", "*)7B,8C", "Закалить")
    }

    should("leave the zone empty when the link has none or it is blank") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Part)),
            composition(node(3, kind = PdmNodeKind.Part), zone = AttributeValue.Text(" ")),
        ).toSpecificationData()

        data.parts.map { it.zone } shouldBe listOf(null, null)
    }

    should("put the complex format before the note of the link") {
        val part = node(2, kind = PdmNodeKind.Part)
        val data = root(
            composition(withDocuments(part, detailDrawingOf("А3х3")), note = AttributeValue.Text("Прим.")),
        ).toSpecificationData()

        data.parts.single().notes shouldBe listOf("*)А3х3", "Прим.")
    }

    should("put the unit of a material first and the note of the link second") {
        val child = node(2, kind = PdmNodeKind.Material)
        val data = root(
            composition(child, Quantity.Exact(2.5), Field.Value("кг"), note = AttributeValue.Text("Лист")),
        ).toSpecificationData()

        data.materials.single().notes shouldBe listOf("кг", "Лист")
    }

    should("show the material of a material form with the position, quantity, unit and note of the composition link") {
        val material = node(7, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Пенополиуретан ППУ 202-1"))
        val form = materialFormOf(5, material)
        val data = root(
            composition(form, Quantity.Exact(2.5), Field.Value("мл"), position = Field.Value(3), note = AttributeValue.Text("Вспенить")),
        ).toSpecificationData()

        data.materials.single() shouldBe MaterialItem(
            position = 3,
            designation = null,
            name = "Пенополиуретан ППУ 202-1",
            quantity = 2.5,
            notes = listOf("мл", "Вспенить"),
        )
    }

    should("take the name of a material form from its material, not from the form itself") {
        val material = node(7, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Пенополиуретан"))
        val form = node(5, kind = PdmNodeKind.MaterialForm, designation = AttributeValue.Text("ФОРМА 001"), name = AttributeValue.Text("Форма"), usages = listOf(madeFrom(material)))
        val data = root(composition(form)).toSpecificationData()

        data.materials.single().name shouldBe "Пенополиуретан"
    }

    should("fail on a material form without a material") {
        val form = node(5, kind = PdmNodeKind.MaterialForm)

        shouldThrow<SpecificationDataException> { root(composition(form)).toSpecificationData() }.message shouldContain "5"
    }

    should("fail on a material form whose usages have no material") {
        val form = node(5, kind = PdmNodeKind.MaterialForm, usages = listOf(madeFrom(node(6, kind = PdmNodeKind.Part))))

        shouldThrow<SpecificationDataException> { root(composition(form)).toSpecificationData() }.message shouldContain "5"
    }

    should("take the first material of a material form that has several") {
        val first = node(7, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Первый"))
        val second = node(8, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Второй"))
        val data = root(composition(materialFormOf(5, first, second))).toSpecificationData()

        data.materials.map { it.name } shouldBe listOf("Первый")
    }

    should("sort material forms and plain materials together by position, then by name") {
        val data = root(
            composition(materialFormOf(5, node(15, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Вата"))), position = Field.Value(2)),
            composition(node(2, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Сталь")), position = Field.Value(1)),
            composition(materialFormOf(6, node(16, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Ааа"))), position = Field.Missing),
            composition(node(3, kind = PdmNodeKind.Material, designation = AttributeValue.Text("Лак")), position = Field.Missing),
        ).toSpecificationData()

        data.materials.map { it.name } shouldBe listOf("Сталь", "Вата", "Ааа", "Лак")
    }

    should("skip an empty or missing note and a not loaded one") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Standard), note = AttributeValue.Empty),
            composition(node(3, kind = PdmNodeKind.Standard), note = null),
            composition(node(4, kind = PdmNodeKind.Standard), note = AttributeValue.Text("   ")),
        ).toSpecificationData()

        data.standards.forEach { it.notes shouldBe emptyList() }
    }
})

// A document usage of the item with the format attribute (null: the format key is not requested).
private fun documentWith(kind: PdmNodeKind, format: AttributeValue?): DocumentUsage = DocumentUsage(
    target = PdmNode(
        id = PdmNodeId(100),
        kind = kind,
        attributes = buildMap {
            put(AttributeKey.Designation, AttributeValue.Text("ДОК"))
            put(AttributeKey.Name, AttributeValue.Text("Документ"))
            format?.let { put(AttributeKey.Format, it) }
        },
    ),
)

private fun specificationOf(format: String) = documentWith(PdmNodeKind.Specification, AttributeValue.Text(format))

private fun specificationOf(format: AttributeValue?) = documentWith(PdmNodeKind.Specification, format)

private fun detailDrawingOf(format: String) = documentWith(PdmNodeKind.DetailDrawing, AttributeValue.Text(format))

private fun detailDrawingOf(format: AttributeValue?) = documentWith(PdmNodeKind.DetailDrawing, format)

// Adds the documents to the item: the item keeps its usages, the documents are the usages of the child.
private fun withDocuments(child: PdmNode, vararg documents: DocumentUsage): PdmNode =
    child.copy(usages = documents.toList())

