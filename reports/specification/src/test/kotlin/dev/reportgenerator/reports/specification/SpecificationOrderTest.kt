package dev.reportgenerator.reports.specification

import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByDesignationAndName
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByMaterialKind
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByStandard
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

private fun part(designation: String?, name: String = "Имя") =
    PartItem(designation = designation, name = name, quantity = 1.0)

private fun assembly(designation: String?, name: String = "Имя") =
    AssemblyItem(designation = designation, name = name, quantity = 1.0)

private fun List<PartItem>.sortedParts(): List<PartItem> = sortedByDesignationAndName()

private fun material(name: String, materialKind: String?, designation: String? = null) =
    MaterialItem(designation = designation, name = name, quantity = 1.0, materialKind = materialKind)

private fun standard(
    name: String,
    standardDesignation: String?,
    productKind: String? = null,
    sizeType: String? = null,
    designation: String? = null,
) = StandardItem(
    designation = designation,
    name = name,
    quantity = 1.0,
    standardDesignation = standardDesignation,
    productKind = productKind,
    sizeType = sizeType,
)

private fun other(
    name: String,
    standardDesignation: String?,
    productKind: String? = null,
) = OtherItem(name = name, quantity = 1.0, standardDesignation = standardDesignation, productKind = productKind)

private fun List<StandardItem>.sortedStandards(): List<StandardItem> = sortedByStandard()

class SpecificationOrderTest : ShouldSpec({
    context("designation order of assemblies and parts") {
        should("order designations by numeric segments, not by text") {
            val low = part("АБВГ.301234.002")
            val high = part("АБВГ.301234.010")

            listOf(high, low).sortedParts() shouldBe listOf(low, high)
        }

        should("order numeric segments as numbers") {
            val two = part("-2")
            val ten = part("-10")

            listOf(ten, two).sortedParts() shouldBe listOf(two, ten)
        }

        should("place a designation without suffix before the same designation with suffix") {
            val plain = part("АБВГ.301234.002")
            val withSuffix = part("АБВГ.301234.002-01")

            listOf(withSuffix, plain).sortedParts() shouldBe listOf(plain, withSuffix)
        }

        should("place cyrillic before latin letters") {
            val cyrillic = part("АБ-1")
            val latin = part("AB-1")

            listOf(latin, cyrillic).sortedParts() shouldBe listOf(cyrillic, latin)
        }

        should("place letter yo right after letter ye") {
            val ye = part("ЕА-1")
            val yo = part("ЁА-1")
            val zhe = part("ЖА-1")

            listOf(zhe, yo, ye).sortedParts() shouldBe listOf(ye, yo, zhe)
        }

        should("ignore letter case") {
            val lower = part("абв-1", name = "Б")
            val upper = part("АБВ-2", name = "А")

            listOf(upper, lower).sortedParts() shouldBe listOf(lower, upper)
        }

        should("place letter segments before digit segments at the same position") {
            val letters = part("АБ")
            val digits = part("1")

            listOf(digits, letters).sortedParts() shouldBe listOf(letters, digits)
        }

        should("sort by designation, then by name") {
            val first = part("АБ.001", name = "Яя")
            val second = part("АБ.002", name = "Аа")
            val sameDesignation = part("АБ.002", name = "Бб")

            listOf(sameDesignation, second, first).sortedParts() shouldBe listOf(first, second, sameDesignation)
        }

        should("place items without designation after items with designation, by name") {
            val withDesignation = part("АБ.010")
            val late = part(null, name = "Бб")
            val early = part(null, name = "Аа")

            listOf(late, withDesignation, early).sortedParts() shouldBe listOf(withDesignation, early, late)
        }

        should("sort assemblies the same way and keep the order of equal items") {
            val second = assembly("АБ.002")
            val first = assembly("АБ.001")
            val twinA = assembly("АБ.003", name = "Одинаковый")
            val twinB = assembly("АБ.003", name = "Одинаковый")

            val sorted = listOf(twinA, second, twinB, first).sortedByDesignationAndName()

            sorted shouldBe listOf(first, second, twinA, twinB)
            (sorted[2] === twinA) shouldBe true
        }
    }

    context("materials") {
        should("order materials by ESKD kind, unknown and empty kinds last") {
            val other = material("Аа", "прочие материалы")
            val ferrous = material("Яя", "металлы черные")
            val empty = material("Бб", null)
            val unknown = material("Вв", "неизвестный вид")

            // unknown and empty are both last: they compare by name (Бб < Вв)
            listOf(unknown, empty, other, ferrous).sortedByMaterialKind() shouldBe listOf(ferrous, other, empty, unknown)
        }

        should("order all twelve kinds in the fixed order") {
            val kinds = listOf(
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
            val items = kinds.map { material("Один", it) }

            items.reversed().sortedByMaterialKind() shouldBe items
        }

        should("order materials of one kind by name with numbers") {
            val nine = material("Сталь 9", "металлы черные")
            val ten = material("Сталь 10", "металлы черные")
            val fortyFive = material("Сталь 45", "металлы черные")

            listOf(fortyFive, ten, nine).sortedByMaterialKind() shouldBe listOf(nine, ten, fortyFive)
        }

        should("order materials of one kind and name by designation") {
            val b = material("Лист", "металлы черные", designation = "Б")
            val a = material("Лист", "металлы черные", designation = "А")

            listOf(b, a).sortedByMaterialKind() shouldBe listOf(a, b)
        }

        should("ignore case and surrounding spaces of material kind") {
            val plastic = material("Аа", "пластмассы и пресс-материалы")
            val ferrous = material("Яя", "  МЕТАЛЛЫ ЧЕРНЫЕ ")

            listOf(plastic, ferrous).sortedByMaterialKind() shouldBe listOf(ferrous, plastic)
        }

        should("sort by name without any material kind") {
            val b = material("Бб", null)
            val a = material("Аа", null)

            listOf(b, a).sortedByMaterialKind() shouldBe listOf(a, b)
        }
    }

    context("standard and other products") {
        should("order standard categories GOST, GOST R, OST, TU, DIN") {
            val din = standard("Болт", "DIN 931")
            val tu = standard("Болт", "ТУ 1-2")
            val ost = standard("Болт", "ОСТ 1 00")
            val gostR = standard("Болт", "ГОСТ Р 50")
            val gost = standard("Болт", "ГОСТ 7798-70")

            listOf(din, tu, ost, gostR, gost).sortedStandards() shouldBe listOf(gost, gostR, ost, tu, din)
        }

        should("check GOST R before GOST so category wins over product kind") {
            val gostR = standard("Болт", "ГОСТ Р 50", productKind = "А")
            val gost = standard("Болт", "ГОСТ 1", productKind = "Я")

            listOf(gostR, gost).sortedStandards() shouldBe listOf(gost, gostR)
        }

        should("require non-letter after the category prefix") {
            val withPrefixLetter = standard("Болт", "ГОСТР 1")
            val gost = standard("Болт", "ГОСТ 1")

            listOf(withPrefixLetter, gost).sortedStandards() shouldBe listOf(gost, withPrefixLetter)
        }

        should("place standards with unknown category flat by name, then designation, after known categories") {
            val withoutStandard = standard("Яблоко", null, designation = "ИЗД.2")
            val unknownBeta = standard("Болт", "ISO 4017", designation = "ИЗД.5")
            val unknownAlpha = standard("Болт", "ISO 4017", designation = "ИЗД.1")
            val known = standard("Гайка", "ГОСТ 1", productKind = "Шайба")

            listOf(withoutStandard, unknownBeta, known, unknownAlpha).sortedStandards() shouldBe
                listOf(known, unknownAlpha, unknownBeta, withoutStandard)
        }

        should("order product kinds alphabetically with cyrillic first and empty kinds last in category") {
            val shayba = standard("Ш", "ГОСТ 1", productKind = "Шайба")
            val bolt = standard("Б", "ГОСТ 2", productKind = "Болт")
            val empty = standard("А", "ГОСТ 3", productKind = null)
            val latin = standard("Л", "ГОСТ 4", productKind = "Bolt")

            listOf(empty, latin, shayba, bolt).sortedStandards() shouldBe listOf(bolt, shayba, latin, empty)
        }

        should("order standard designations naturally inside one product kind") {
            val newer = standard("Шайба", "ГОСТ 15589-70", productKind = "Шайба")
            val older = standard("Шайба", "ГОСТ 397-79", productKind = "Шайба")

            listOf(newer, older).sortedStandards() shouldBe listOf(older, newer)
        }

        should("order sizes by numbers with x, х and × as separators") {
            val m16 = standard("Болт", "ГОСТ 7798-70", sizeType = "М16x30")
            val m12x30 = standard("Болт", "ГОСТ 7798-70", sizeType = "М12x30")
            val m12times25 = standard("Болт", "ГОСТ 7798-70", sizeType = "М12×25")

            listOf(m16, m12x30, m12times25).sortedStandards() shouldBe listOf(m12times25, m12x30, m16)
        }

        should("treat cyrillic х as a size separator") {
            val latinX = standard("Болт", "ГОСТ 7798-70", sizeType = "М12x30")
            val cyrillicX = standard("Болт", "ГОСТ 7798-70", sizeType = "М12х25")

            listOf(latinX, cyrillicX).sortedStandards() shouldBe listOf(cyrillicX, latinX)
        }

        should("compare decimal sizes as numbers") {
            val two = standard("Шайба", "ГОСТ 1", sizeType = "3,2x28.4")
            val fifteen = standard("Шайба", "ГОСТ 1", sizeType = "3,15x28.4")

            listOf(two, fifteen).sortedStandards() shouldBe listOf(fifteen, two)
        }

        should("place items without size after items with size, then by name") {
            val withoutSize = standard("Аа", "ГОСТ 1", productKind = "Болт")
            val withSize = standard("Яя", "ГОСТ 1", productKind = "Болт", sizeType = "6")

            listOf(withoutSize, withSize).sortedStandards() shouldBe listOf(withSize, withoutSize)
        }

        should("apply the same standard rules to other products") {
            val gost = other("Заглушка", "ГОСТ 1", productKind = "Шайба")
            val tu = other("Заглушка", "ТУ 1")

            listOf(tu, gost).sortedByStandard() shouldBe listOf(gost, tu)
        }

        should("degrade to name order when no attribute is filled") {
            val b = standard("Болт М8", null, designation = "Б")
            val a = standard("Болт М6", null, designation = "А")

            listOf(b, a).sortedStandards() shouldBe listOf(a, b)
        }
    }

    context("natural string order") {
        should("count a decimal fraction as one number only when decimals are on") {
            val plain = NaturalStringOrder(decimals = false)
            val decimals = NaturalStringOrder(decimals = true)

            // "3,15" vs "3,2": two numbers 3,15 and 3,2 as text parts (15 > 2) or as decimals (3,15 < 3,2)
            (plain.compare("3,15", "3,2") > 0) shouldBe true
            (decimals.compare("3,15", "3,2") < 0) shouldBe true
        }
    }
})
