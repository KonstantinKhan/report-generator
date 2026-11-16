package dev.reportgenerator.reports.specification

import java.math.BigDecimal

// The order of the rows inside a section (the rules of the sheet, not the position from Loodsman: the position is only shown).
// - assemblies and parts: by designation (items without one go after the others), then by name, in natural order;
// - materials: by the ESKD material kind (12 fixed kinds, unknown or empty last), then by name, then by designation;
// - standard and other products: by the category of the standard (ГОСТ, ГОСТ Р, ОСТ, ТУ, DIN) taken from the standard designation,
//   inside a known category by the product kind (empty last), the standard designation, the size type and the name;
//   items without a known category go after all of them, flat by name, then by designation.
// Documentation, kits and executions keep their own order (see ProductNodeSpecification). The sorts are stable.
internal object SpecificationOrder {
    private val names = NaturalStringOrder(decimals = false)
    private val sizes = NaturalStringOrder(decimals = true)

    private val byDesignation: Comparator<PositionedItem> =
        compareBy<PositionedItem, String?>(nullsLast<String>(names)) { it.designation }
    private val byName: Comparator<PositionedItem> = compareBy<PositionedItem, String>(names) { it.name }

    private val designationFirst: Comparator<PositionedItem> = byDesignation.andThen(byName)

    // The order of the ESKD material kinds; an unknown or empty kind goes last.
    private val materialKinds = listOf(
        "металлы черные",
        "металлы магнитоэлектрические и ферромагнитные",
        "металлы цветные, благородные и редкие",
        "кабели, провода и шнуры (кроме готовых изделий)",
        "пластмассы и пресс-материалы",
        "бумажные и текстильные материалы",
        "лесоматериалы",
        "резиновые и кожевенные материалы",
        "минеральные, керамические и стеклянные материалы",
        "лаки, краски, нефтепродукты и химикаты",
        "металлические, неметаллические порошки",
        "прочие материалы",
    )
    private val materials: Comparator<MaterialItem> =
        compareBy<MaterialItem> { materialKindIndex(it.materialKind) }.andThen(byName).andThen(byDesignation)

    // A standard of a known category: product kind (empty last), standard designation, size type, name.
    private val knownStandard: Comparator<CatalogItem> =
        compareBy<CatalogItem, String?>(nullsLast<String>(names)) { it.productKind?.takeIf { s -> s.isNotBlank() } }
            .andThen(compareBy<CatalogItem, String?>(nullsLast<String>(names)) { it.standardDesignation })
            .andThen(compareBy<CatalogItem, String?>(nullsLast<String>(sizes)) { it.sizeType?.let(::normalizeSize) })
            .andThen(byName)

    // A standard without a recognized category: flat by name, then by designation.
    private val unknownStandard: Comparator<CatalogItem> = byName.andThen(byDesignation)

    private val standards: Comparator<CatalogItem> = Comparator { a, b ->
        val leftCategory = standardCategoryIndex(a.standardDesignation)
        val rightCategory = standardCategoryIndex(b.standardDesignation)
        val byCategory = leftCategory.compareTo(rightCategory)
        when {
            byCategory != 0 -> byCategory
            leftCategory == UNKNOWN_STANDARD_CATEGORY -> unknownStandard.compare(a, b)
            else -> knownStandard.compare(a, b)
        }
    }

    // The size separator "x" / "х" / "×" between digits or spaces (М12x25) becomes a space.
    private val sizeSeparator = Regex("(?<=[\\d\\s])[xXхХ×](?=[\\d\\s])")

    fun <T : PositionedItem> List<T>.sortedByDesignationAndName(): List<T> = sortedWith(designationFirst)

    fun List<MaterialItem>.sortedByMaterialKind(): List<MaterialItem> = sortedWith(materials)

    fun <T : CatalogItem> List<T>.sortedByStandard(): List<T> = sortedWith(standards)

    private fun materialKindIndex(value: String?): Int {
        val key = value?.trim()?.lowercase() ?: return materialKinds.size
        return materialKinds.indexOf(key).takeIf { it >= 0 } ?: materialKinds.size
    }

    private fun normalizeSize(value: String): String = value.trim().replace(sizeSeparator, " ")

    private fun <T> Comparator<in T>.andThen(next: Comparator<in T>): Comparator<T> = Comparator<T> { a, b -> compare(a, b) }.thenComparator { a, b -> next.compare(a, b) }
}

// Natural order of texts: a text is cut into runs of letters and runs of digits, other characters are dropped. A run of
// letters goes before a run of digits, runs of digits compare as numbers, the case is ignored, Cyrillic goes before
// Latin, "ё" follows "е". decimals = true: a run like "3,2" or "28.4" is one number with a fraction (size types only).
internal class NaturalStringOrder(decimals: Boolean) : Comparator<String> {
    private val token = if (decimals) Regex("\\p{L}+|[0-9]+(?:[.,][0-9]+)?") else Regex("\\p{L}+|[0-9]+")

    override fun compare(a: String, b: String): Int {
        val left = tokenize(a)
        val right = tokenize(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val result = compareTokens(left[i], right[i])
            if (result != 0) return result
        }
        // The shorter sequence goes first (without the suffix "-01" before with it).
        return left.size.compareTo(right.size)
    }

    private fun tokenize(s: String): List<String> = token.findAll(s).map { it.value }.toList()

    private fun compareTokens(left: String, right: String): Int {
        val leftIsDigits = left[0] in '0'..'9'
        val rightIsDigits = right[0] in '0'..'9'
        if (leftIsDigits != rightIsDigits) return if (leftIsDigits) 1 else -1
        if (leftIsDigits) {
            val byValue = BigDecimal(left.replace(',', '.')).compareTo(BigDecimal(right.replace(',', '.')))
            // "01" and "1" are equal by value: the shorter goes first, so the order stays deterministic.
            return if (byValue != 0) byValue else left.length.compareTo(right.length)
        }
        for (i in 0 until minOf(left.length, right.length)) {
            val result = letterKey(left[i]).compareTo(letterKey(right[i]))
            if (result != 0) return result
        }
        return left.length.compareTo(right.length)
    }

    // Groups: Cyrillic, Latin, other letters. Inside Cyrillic "ё" goes right after "е".
    private fun letterKey(c: Char): Int {
        val lower = c.lowercaseChar()
        val group = when (lower) {
            in 'Ѐ'..'ӿ' -> 0
            in 'a'..'z' -> 1
            else -> 2
        }
        val position = if (lower == 'ё') 'е'.code * 2 + 1 else lower.code * 2
        return group * GROUP_SHIFT + position
    }

    private companion object {
        const val GROUP_SHIFT = 0x40000
    }
}
