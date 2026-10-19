package dev.reportgenerator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssemblyTest {

    private fun part(id: Int) = Part(id, Designation.Value("P.$id"), Name.Value("Part $id"), "active", "1.0")

    private fun assembly(id: Int, components: Components) =
        Assembly(id, Designation.Value("A.$id"), Name.Value("Assembly $id"), "active", "1.0", components)

    private fun <T : Item> component(item: T, position: Int) =
        Component(item, Quantity.Exact(1.0), Field.Missing, Field.Value(position))

    @Test
    fun `loaded assembly exposes parts, assemblies and all directly`() {
        val part = component(part(2), position = 2)
        val nested = component(assembly(3, Components.NotLoaded), position = 1)
        val root = assembly(1, Components.Loaded(listOf(part), listOf(nested), emptyList(), emptyList()))

        assertTrue(root.isLoaded)
        assertEquals(listOf(part), root.parts)
        assertEquals(listOf(nested), root.assemblies)
        assertEquals(listOf(nested, part), root.all)
    }

    @Test
    fun `loaded assembly without children has empty lists`() {
        val root = assembly(1, Components.Loaded(emptyList(), emptyList(), emptyList(), emptyList()))

        assertTrue(root.isLoaded)
        assertEquals(emptyList(), root.parts)
        assertEquals(emptyList(), root.assemblies)
        assertEquals(emptyList(), root.all)
    }

    @Test
    fun `not loaded assembly throws with clear message on parts, assemblies and all`() {
        val root = assembly(7, Components.NotLoaded)

        assertFalse(root.isLoaded)
        listOf({ root.parts }, { root.assemblies }, { root.all }).forEach { access ->
            val error = assertFailsWith<IllegalStateException> { access() }
            assertTrue("id=7" in error.message.orEmpty() && "not loaded" in error.message.orEmpty())
        }
    }

    @Test
    fun `standard products are exposed directly and included in all`() {
        val standard = component(
            StandardProduct(5, Designation.Missing, Name.Value("Болт"), "active", "1.0"),
            position = 1
        )
        val part = component(part(2), position = 2)
        val root = assembly(1, Components.Loaded(listOf(part), emptyList(), listOf(standard), emptyList()))

        assertEquals(listOf(standard), root.standardProducts)
        assertEquals(listOf(standard, part), root.all)
    }

    @Test
    fun `not loaded assembly throws on standardProducts`() {
        val error = assertFailsWith<IllegalStateException> { assembly(9, Components.NotLoaded).standardProducts }
        assertTrue("id=9" in error.message.orEmpty())
    }

    @Test
    fun `other products are exposed directly and included in all`() {
        val other = component(
            OtherProduct(6, Designation.Missing, Name.Value("Припой"), "active", "1.0"),
            position = 1
        )
        val standard = component(
            StandardProduct(5, Designation.Missing, Name.Value("Болт"), "active", "1.0"),
            position = 2
        )
        val root = assembly(1, Components.Loaded(emptyList(), emptyList(), listOf(standard), listOf(other)))

        assertEquals(listOf(other), root.otherProducts)
        assertEquals(listOf(other, standard), root.all)
    }

    @Test
    fun `not loaded assembly throws on otherProducts`() {
        val error = assertFailsWith<IllegalStateException> { assembly(4, Components.NotLoaded).otherProducts }
        assertTrue("id=4" in error.message.orEmpty())
    }
}
