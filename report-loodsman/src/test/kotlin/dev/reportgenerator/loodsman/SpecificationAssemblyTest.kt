package dev.reportgenerator.loodsman

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpecificationAssemblyTest {

    @Test
    fun `maps known Loodsman type names to item kinds`() {
        assertEquals("PART", mapItemKind("Деталь"))
        assertEquals("STANDARD", mapItemKind("Стандартное изделие"))
        assertEquals("OTHER", mapItemKind("Прочее изделие"))
        assertEquals("MATERIAL", mapItemKind("Материал по КД"))
    }

    @Test
    fun `mapping is case and whitespace insensitive`() {
        assertEquals("PART", mapItemKind("  деталь  "))
        assertEquals("STANDARD", mapItemKind("СТАНДАРТНОЕ ИЗДЕЛИЕ"))
    }

    @Test
    fun `unknown or missing type name maps to null`() {
        assertNull(mapItemKind("Сборочная единица"))
        assertNull(mapItemKind(null))
    }

    @Test
    fun `builds items from resolved attributes and skips children with unrecognized type`() {
        val children = listOf(
            ChildLink(idLink = 10, idChild = 100, idType = 1),
            ChildLink(idLink = 11, idChild = 101, idType = 1),
            ChildLink(idLink = 12, idChild = 102, idType = 1),
        )
        val typeNameByObjectId = mapOf(100 to "Деталь", 101 to "Материал по КД", 102 to "Сборочная единица")
        // 101 is a Material: product field IS the Наименование, no separate name attribute is fetched for it.
        val designationByObjectId = mapOf(100 to "A.1", 101 to "Клей", 102 to "A.3")
        val nameByObjectId = mapOf(100 to "Болт", 102 to "Подсборка")
        val quantityByLinkId = mapOf(10 to 4.0, 11 to 1.5, 12 to 1.0)
        val unitByLinkId = mapOf(11 to "кг")

        val items = buildItems(children, typeNameByObjectId, designationByObjectId, nameByObjectId, quantityByLinkId, unitByLinkId)

        assertEquals(2, items.size)
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
}
