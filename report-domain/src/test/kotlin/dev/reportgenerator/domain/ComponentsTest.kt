package dev.reportgenerator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentsTest {

    @Test
    fun `components with position are sorted first by position number`() {
        // Arrange
        val part1 = Part(1, Designation.Value("P1"), Name.Value("Part 1"), "active", "1.0")
        val part2 = Part(2, Designation.Value("P2"), Name.Value("Part 2"), "active", "1.0")
        val part3 = Part(3, Designation.Value("P3"), Name.Value("Part 3"), "active", "1.0")

        val comp3 = Component(part3, Quantity.Missing, Field.Missing, Field.Value(3))
        val comp1 = Component(part1, Quantity.Missing, Field.Missing, Field.Value(1))
        val comp2 = Component(part2, Quantity.Missing, Field.Missing, Field.Value(2))

        val loaded = Components.Loaded(listOf(comp3, comp1, comp2), emptyList(), emptyList(), emptyList())

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(3, sorted.size)
        assertEquals(1, (sorted[0].item as Part).id)
        assertEquals(2, (sorted[1].item as Part).id)
        assertEquals(3, (sorted[2].item as Part).id)
    }

    @Test
    fun `components without position but with designation come after positional components`() {
        // Arrange
        val part1 = Part(1, Designation.Value("B"), Name.Value("Part 1"), "active", "1.0")
        val part2 = Part(2, Designation.Value("A"), Name.Value("Part 2"), "active", "1.0")
        val part3 = Part(3, Designation.Value("C"), Name.Value("Part 3"), "active", "1.0")
        val part4 = Part(4, Designation.Missing, Name.Value("Part 4"), "active", "1.0")

        val compPos = Component(part4, Quantity.Missing, Field.Missing, Field.Value(1))
        val compDesignB = Component(part1, Quantity.Missing, Field.Missing, Field.Missing)
        val compDesignA = Component(part2, Quantity.Missing, Field.Missing, Field.Missing)
        val compDesignC = Component(part3, Quantity.Missing, Field.Missing, Field.Missing)

        val loaded = Components.Loaded(listOf(compDesignB, compPos, compDesignC, compDesignA), emptyList(), emptyList(), emptyList())

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(4, sorted.size)
        // First: position
        assertEquals(4, (sorted[0].item as Part).id)
        // Then: designations, alphabetically case-insensitive
        assertEquals(2, (sorted[1].item as Part).id)  // "A"
        assertEquals(1, (sorted[2].item as Part).id)  // "B"
        assertEquals(3, (sorted[3].item as Part).id)  // "C"
    }

    @Test
    fun `components without position and designation come last, sorted by name`() {
        // Arrange
        val part1 = Part(1, Designation.Missing, Name.Value("Zebra"), "active", "1.0")
        val part2 = Part(2, Designation.Missing, Name.Value("Apple"), "active", "1.0")
        val part3 = Part(3, Designation.Missing, Name.Value("Banana"), "active", "1.0")

        val comp1 = Component(part1, Quantity.Missing, Field.Missing, Field.Missing)
        val comp2 = Component(part2, Quantity.Missing, Field.Missing, Field.Missing)
        val comp3 = Component(part3, Quantity.Missing, Field.Missing, Field.Missing)

        val loaded = Components.Loaded(listOf(comp1, comp2, comp3), emptyList(), emptyList(), emptyList())

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(3, sorted.size)
        assertEquals(2, (sorted[0].item as Part).id)  // "Apple"
        assertEquals(3, (sorted[1].item as Part).id)  // "Banana"
        assertEquals(1, (sorted[2].item as Part).id)  // "Zebra"
    }

    @Test
    fun `mixed groups - positions, designations, names`() {
        // Arrange
        val withPos2 = Part(2, Designation.Value("D"), Name.Value("With pos 2"), "active", "1.0")
        val withPos1 = Part(1, Designation.Value("C"), Name.Value("With pos 1"), "active", "1.0")
        val withDesignB = Part(3, Designation.Value("B"), Name.Value("With design B"), "active", "1.0")
        val withDesignA = Part(4, Designation.Value("A"), Name.Value("With design A"), "active", "1.0")
        val withName1 = Part(5, Designation.Missing, Name.Value("Name Z"), "active", "1.0")
        val withName2 = Part(6, Designation.Missing, Name.Value("Name A"), "active", "1.0")

        val loaded = Components.Loaded(
            listOf(
                Component(withPos2, Quantity.Missing, Field.Missing, Field.Value(2)),
                Component(withDesignB, Quantity.Missing, Field.Missing, Field.Missing),
                Component(withName1, Quantity.Missing, Field.Missing, Field.Missing),
                Component(withPos1, Quantity.Missing, Field.Missing, Field.Value(1)),
                Component(withDesignA, Quantity.Missing, Field.Missing, Field.Missing),
                Component(withName2, Quantity.Missing, Field.Missing, Field.Missing)
            ),
            emptyList(),
            emptyList(),
            emptyList()
        )

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(6, sorted.size)
        // Positions (1, 2)
        assertEquals(1, (sorted[0].item as Part).id)
        assertEquals(2, (sorted[1].item as Part).id)
        // Designations (A, B)
        assertEquals(4, (sorted[2].item as Part).id)
        assertEquals(3, (sorted[3].item as Part).id)
        // Names (A, Z)
        assertEquals(6, (sorted[4].item as Part).id)
        assertEquals(5, (sorted[5].item as Part).id)
    }

    @Test
    fun `equal keys - determinism by designation, name, id`() {
        // Arrange - same position, same designation, same name - should sort by id
        val part1 = Part(1, Designation.Missing, Name.Value("Same"), "active", "1.0")
        val part2 = Part(2, Designation.Missing, Name.Value("Same"), "active", "1.0")
        val part3 = Part(3, Designation.Missing, Name.Value("Same"), "active", "1.0")

        val comp1 = Component(part1, Quantity.Missing, Field.Missing, Field.Missing)
        val comp3 = Component(part3, Quantity.Missing, Field.Missing, Field.Missing)
        val comp2 = Component(part2, Quantity.Missing, Field.Missing, Field.Missing)

        val loaded = Components.Loaded(listOf(comp1, comp3, comp2), emptyList(), emptyList(), emptyList())

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(3, sorted.size)
        assertEquals(1, (sorted[0].item as Part).id)
        assertEquals(2, (sorted[1].item as Part).id)
        assertEquals(3, (sorted[2].item as Part).id)
    }

    @Test
    fun `blank designation treated as absent`() {
        // Arrange - blank designation should be treated as missing
        val partWithBlank = Part(1, Designation.Value(""), Name.Value("Part"), "active", "1.0")
        val partWithDesign = Part(2, Designation.Value("A"), Name.Value("Part"), "active", "1.0")

        val compBlank = Component(partWithBlank, Quantity.Missing, Field.Missing, Field.Missing)
        val compDesign = Component(partWithDesign, Quantity.Missing, Field.Missing, Field.Missing)

        val loaded = Components.Loaded(listOf(compBlank, compDesign), emptyList(), emptyList(), emptyList())

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(2, sorted.size)
        // Blank should be treated as absent, so comes last
        assertEquals(2, (sorted[0].item as Part).id)  // Has designation "A"
        assertEquals(1, (sorted[1].item as Part).id)  // Blank designation
    }

    @Test
    fun `empty Loaded has empty all`() {
        // Arrange
        val loaded = Components.Loaded(emptyList(), emptyList(), emptyList(), emptyList())

        // Act
        val all = loaded.all

        // Assert
        assertEquals(0, all.size)
    }

    @Test
    fun `case insensitive ordering for designations`() {
        // Arrange
        val part1 = Part(1, Designation.Value("alpha"), Name.Value("Part 1"), "active", "1.0")
        val part2 = Part(2, Designation.Value("BETA"), Name.Value("Part 2"), "active", "1.0")
        val part3 = Part(3, Designation.Value("Gamma"), Name.Value("Part 3"), "active", "1.0")

        val loaded = Components.Loaded(
            listOf(
                Component(part2, Quantity.Missing, Field.Missing, Field.Missing),
                Component(part3, Quantity.Missing, Field.Missing, Field.Missing),
                Component(part1, Quantity.Missing, Field.Missing, Field.Missing)
            ),
            emptyList(),
            emptyList(),
            emptyList()
        )

        // Act
        val sorted = loaded.all

        // Assert
        assertEquals(3, sorted.size)
        assertEquals(1, (sorted[0].item as Part).id)  // "alpha"
        assertEquals(2, (sorted[1].item as Part).id)  // "BETA"
        assertEquals(3, (sorted[2].item as Part).id)  // "Gamma"
    }

    @Test
    fun `parts and assemblies mixed in all - sorted together`() {
        // Arrange - test that both parts and assemblies go into 'all' and are sorted together
        val part1 = Part(1, Designation.Value("PART"), Name.Value("Part 1"), "active", "1.0")
        val assembly1 = Assembly(
            2,
            Designation.Value("ASM"),
            Name.Value("Assembly"),
            "active",
            "1.0",
            Components.NotLoaded
        )
        val part2 = Part(3, Designation.Missing, Name.Value("Part 2"), "active", "1.0")

        val comp1Part = Component(part1, Quantity.Missing, Field.Missing, Field.Missing)
        val comp2Asm = Component(assembly1, Quantity.Missing, Field.Missing, Field.Missing)
        val comp3Part = Component(part2, Quantity.Missing, Field.Missing, Field.Missing)

        val loaded = Components.Loaded(
            parts = listOf(comp1Part, comp3Part),
            assemblies = listOf(comp2Asm),
            standardProducts = emptyList(),
            otherProducts = emptyList()
        )

        // Act
        val sorted = loaded.all

        // Assert - all should have 3 items: ASM (designation), PART (designation), Part2 (name)
        assertEquals(3, sorted.size)
        // ASM comes first (has designation)
        assertEquals(2, (sorted[0].item as Assembly).id)
        // PART comes next (has designation)
        assertEquals(1, (sorted[1].item as Part).id)
        // Part 2 comes last (no designation, sorted by name)
        assertEquals(3, (sorted[2].item as Part).id)
    }
}
