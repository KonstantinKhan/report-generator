package dev.reportgenerator.loodsman

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpecificationAssemblyTest {

    @Test
    fun `maps known Loodsman type names to item kinds`() {
        assertEquals("PART", mapItemKind("Деталь"))
        assertEquals("ASSEMBLY", mapItemKind("Сборочная единица"))
        assertEquals("STANDARD", mapItemKind("Стандартное изделие"))
        assertEquals("OTHER", mapItemKind("Прочее изделие"))
        assertEquals("MATERIAL", mapItemKind("Материал по КД"))
        assertEquals("DOCUMENTATION", mapItemKind("Сборочный чертеж"))
        assertEquals("COMPLEX", mapItemKind("Комплекс"))
        assertEquals("SET", mapItemKind("Комплект"))
    }

    @Test
    fun `mapping is case and whitespace insensitive`() {
        assertEquals("PART", mapItemKind("  деталь  "))
        assertEquals("STANDARD", mapItemKind("СТАНДАРТНОЕ ИЗДЕЛИЕ"))
        assertEquals("ASSEMBLY", mapItemKind(" Сборочная Единица "))
    }

    @Test
    fun `unknown or missing type name maps to null`() {
        assertNull(mapItemKind("Документ"))
        assertNull(mapItemKind(null))
    }

    @Test
    fun `builds items from resolved attributes and skips only children with unrecognized type`() {
        val children = listOf(
            ChildLink(idLink = 10, idChild = 100, idType = 1),
            ChildLink(idLink = 11, idChild = 101, idType = 1),
            ChildLink(idLink = 12, idChild = 102, idType = 1),
            ChildLink(idLink = 13, idChild = 103, idType = 1),
        )
        val typeNameByObjectId = mapOf(100 to "Деталь", 101 to "Материал по КД", 102 to "Сборочная единица", 103 to "Документ")
        // 101 is a Material: product field IS the Наименование, no separate name attribute is fetched for it.
        val designationByObjectId = mapOf(100 to "A.1", 101 to "Клей", 102 to "A.3", 103 to "D.1")
        val nameByObjectId = mapOf(100 to "Болт", 102 to "Подсборка")
        val quantityByLinkId = mapOf(10 to 4.0, 11 to 1.5, 12 to 1.0, 13 to 1.0)
        val unitByLinkId = mapOf(11 to "кг")

        val items = buildItems(children, typeNameByObjectId, designationByObjectId, nameByObjectId, quantityByLinkId, unitByLinkId)

        assertEquals(3, items.size)
        assertEquals("A.1", items[0].designation)
        assertEquals("Болт", items[0].name)
        assertEquals("PART", items[0].kind)
        assertEquals(4.0, items[0].quantity)
        assertNull(items[0].unit)
        // Material: "Обозначение" column stays empty, only "Наименование" is filled.
        assertNull(items[1].designation)
        assertEquals("Клей", items[1].name)
        assertEquals("MATERIAL", items[1].kind)
        assertEquals(1.5, items[1].quantity)
        assertEquals("кг", items[1].unit)
        // Assembly: key attribute is Обозначение, name comes from the separate Наименование attribute.
        assertEquals("A.3", items[2].designation)
        assertEquals("Подсборка", items[2].name)
        assertEquals("ASSEMBLY", items[2].kind)
        assertEquals(1.0, items[2].quantity)
    }

    @Test
    fun `throws when designation is missing for a recognized child`() {
        val children = listOf(ChildLink(idLink = 10, idChild = 100, idType = 1))
        val typeNameByObjectId = mapOf(100 to "Деталь")

        assertFailsWith<LoodsmanApiException> {
            buildItems(children, typeNameByObjectId, emptyMap(), mapOf(100 to "Болт"), mapOf(10 to 1.0))
        }
    }

    @Test
    fun `throws when quantity is missing for a recognized child`() {
        val children = listOf(ChildLink(idLink = 10, idChild = 100, idType = 1))
        val typeNameByObjectId = mapOf(100 to "Деталь")

        assertFailsWith<LoodsmanApiException> {
            buildItems(children, typeNameByObjectId, mapOf(100 to "A.1"), mapOf(100 to "Болт"), emptyMap())
        }
    }

    @Test
    fun `non-assembly child leaves designation empty and uses product value as name`() {
        val children = listOf(ChildLink(idLink = 10, idChild = 100, idType = 1))
        val typeNameByObjectId = mapOf(100 to "Материал по КД")
        val designationByObjectId = mapOf(100 to "A.1")

        val items = buildItems(children, typeNameByObjectId, designationByObjectId, emptyMap(), mapOf(10 to 1.0))

        assertNull(items[0].designation)
        assertEquals("A.1", items[0].name)
    }

    @Test
    fun `throws when both name and designation are missing for a non-assembly child`() {
        val children = listOf(ChildLink(idLink = 10, idChild = 100, idType = 1))
        val typeNameByObjectId = mapOf(100 to "Материал по КД")

        assertFailsWith<LoodsmanApiException> {
            buildItems(children, typeNameByObjectId, emptyMap(), emptyMap(), mapOf(10 to 1.0))
        }
    }

    @Test
    fun `standard and other items take designation from attribute and name from key attribute`() {
        val children = listOf(
            ChildLink(idLink = 10, idChild = 100, idType = 1),
            ChildLink(idLink = 11, idChild = 101, idType = 1),
            ChildLink(idLink = 12, idChild = 102, idType = 1),
        )
        val types = mapOf(100 to "Стандартное изделие", 101 to "Прочее изделие", 102 to "Стандартное изделие")
        val products = mapOf(100 to "Болт М6", 101 to "Пломба", 102 to "Шайба")
        val productDesignations = mapOf(100 to "ГОСТ 7798-70", 101 to "ТУ 1-2-3")

        val items = buildItems(
            children, types, products, emptyMap(), mapOf(10 to 8.0, 11 to 1.0, 12 to 2.0),
            productDesignationByObjectId = productDesignations,
        )

        assertEquals("ГОСТ 7798-70", items[0].designation)
        assertEquals("Болт М6", items[0].name)
        assertEquals("STANDARD", items[0].kind)
        assertEquals("ТУ 1-2-3", items[1].designation)
        assertEquals("Пломба", items[1].name)
        assertEquals("OTHER", items[1].kind)
        assertNull(items[2].designation, "missing attribute leaves the designation empty")
        assertEquals("Шайба", items[2].name)
    }

    @Test
    fun `material ignores product designation attribute`() {
        val children = listOf(ChildLink(idLink = 10, idChild = 100, idType = 1))

        val items = buildItems(
            children, mapOf(100 to "Материал по КД"), mapOf(100 to "Сталь 45"), emptyMap(), mapOf(10 to 0.35),
            productDesignationByObjectId = mapOf(100 to "ГОСТ 1050"),
        )

        assertNull(items[0].designation)
        assertEquals("Сталь 45", items[0].name)
    }

    @Test
    fun `complex set and drawing are keyed by designation with separate name`() {
        val children = listOf(
            ChildLink(idLink = 10, idChild = 100, idType = 1),
            ChildLink(idLink = 11, idChild = 101, idType = 1),
            ChildLink(idLink = 12, idChild = 102, idType = 1),
        )
        val types = mapOf(100 to "Комплекс", 101 to "Комплект", 102 to "Сборочный чертеж")
        val products = mapOf(100 to "К.1", 101 to "КТ.1", 102 to "СБ.1")
        val names = mapOf(100 to "Комплекс А", 101 to "Комплект Б", 102 to "Сборочный чертёж В")

        // the drawing link carries no quantity
        val items = buildItems(children, types, products, names, mapOf(10 to 1.0, 11 to 2.0))

        assertEquals(listOf("COMPLEX", "SET", "DOCUMENTATION"), items.map { it.kind })
        assertEquals(listOf("К.1", "КТ.1", "СБ.1"), items.map { it.designation })
        assertEquals(listOf("Комплекс А", "Комплект Б", "Сборочный чертёж В"), items.map { it.name })
        assertEquals(2.0, items[1].quantity)
    }

    @Test
    fun `missing quantity still fails for non-documentation`() {
        assertFailsWith<LoodsmanApiException> {
            buildItems(
                listOf(ChildLink(idLink = 10, idChild = 100, idType = 1)),
                mapOf(100 to "Комплект"), mapOf(100 to "КТ.1"), emptyMap(), emptyMap(),
            )
        }
    }
}
