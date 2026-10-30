package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.ReplacementType
import dev.reportgenerator.domain.ReplacementVariant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// The composition usage of the root with the given link id and position (Field.Missing / NotLoaded for the other cases).
private fun usage(linkId: Int, position: Field<Int> = Field.Value(linkId)) = CompositionUsage(
    target = PdmNode(id = PdmNodeId(linkId), kind = PdmNodeKind.Standard),
    position = position,
    quantity = Quantity.Exact(1.0),
    unit = Field.Missing,
    linkId = linkId,
)

private fun at(linkId: Int, position: Int) = usage(linkId, Field.Value(position))

// The composition usage of the root with the application method attribute value.
private fun withMethod(linkId: Int, position: Int, method: AttributeValue) = CompositionUsage(
    target = PdmNode(id = PdmNodeId(linkId), kind = PdmNodeKind.Standard),
    position = Field.Value(position),
    quantity = Quantity.Exact(1.0),
    unit = Field.Missing,
    linkId = linkId,
    attributes = mapOf(LinkAttributeKey.ApplicationMethod to method),
)

private fun rootOf(usages: List<CompositionUsage>, groups: List<ReplacementGroup>) = PdmNode(
    id = PdmNodeId(1),
    kind = PdmNodeKind.Assembly,
    usages = usages,
    replacements = groups,
)

// A group: the basic variant, then one non-basic variant per list (the ids by the group id).
private fun group(
    id: Int,
    basic: List<Int>,
    vararg nonBasic: List<Int>,
    type: ReplacementType = ReplacementType.Permissible,
): ReplacementGroup = ReplacementGroup(
    id = id,
    name = null,
    type = type,
    variants = listOf(ReplacementVariant(id = id * 10, name = null, basic = true, linkIds = basic, inCurrentConfiguration = true)) +
        nonBasic.mapIndexed { index, linkIds ->
            ReplacementVariant(
                id = id * 10 + index + 1, name = null, basic = false, linkIds = linkIds, inCurrentConfiguration = true,
            )
        },
    inCurrentConfiguration = true,
)

class ReplacementNotesTest : ShouldSpec({
    should("give the basic member the positions of the non-basic variant and the non-basic members the replaced positions") {
        // Болт (поз. 5) заменяется вариантом Болт 2 + Шплинт (поз. 7 совместно с поз. 8)
        val root = rootOf(
            usages = listOf(at(10045, 5), at(10037, 7), at(10046, 8)),
            groups = listOf(group(1, listOf(10045), listOf(10037, 10046))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            10045 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7 совместно с поз. 8")),
            10037 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 8 взамен поз. 5")),
            10046 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 7 взамен поз. 5")),
        )
    }

    should("join the non-basic variants of one group with или") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7), at(3, 8), at(4, 9)),
            groups = listOf(group(1, listOf(1), listOf(2, 3), listOf(4))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7 совместно с поз. 8 или поз. 9")),
            2 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 8 взамен поз. 5")),
            3 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 7 взамен поз. 5")),
            4 to ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5")),
        )
    }

    should("list every basic position of the replaced variant") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 6), at(3, 7)),
            groups = listOf(group(1, listOf(1, 2), listOf(3))),
        )

        ReplacementNotes.of(root)[3] shouldBe ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5, 6"))
    }

    should("join the replacement texts of two groups for a non-basic member with или") {
        // поз. 7 заменяет поз. 5 (группа 1, вариант с поз. 8) и поз. 6 (группа 2)
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 6), at(3, 7), at(4, 8)),
            groups = listOf(group(1, listOf(1), listOf(3, 4)), group(2, listOf(2), listOf(3))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7 совместно с поз. 8")),
            2 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7")),
            3 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 8 взамен поз. 5 или взамен поз. 6")),
            4 to ReplacementNote(isNonBasic = true, texts = listOf("примен. с поз. 7 взамен поз. 5")),
        )
    }

    should("join the fragments of two groups that share a basic member with или") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7), at(5, 9)),
            groups = listOf(group(1, listOf(1), listOf(2)), group(2, listOf(1), listOf(5))),
        )

        ReplacementNotes.of(root)[1] shouldBe ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7 или поз. 9"))
    }

    should("give a member both texts when it is basic in one group and non-basic in another") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7), at(3, 8)),
            groups = listOf(group(1, listOf(1), listOf(2)), group(2, listOf(3), listOf(1))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = true, texts = listOf("допуск. замена на поз. 7", "примен. взамен поз. 8")),
            2 to ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5")),
            3 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 5")),
        )
    }

    should("ignore the groups that are not permissible") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7)),
            groups = listOf(
                group(1, listOf(1), listOf(2), type = ReplacementType.Consumer),
                group(2, listOf(1), listOf(2), type = ReplacementType.Production),
            ),
        )

        ReplacementNotes.of(root) shouldBe emptyMap()
    }

    should("give no notes to a permissible group without a non-basic variant") {
        val root = rootOf(
            usages = listOf(at(1, 5)),
            groups = listOf(group(1, listOf(1))),
        )

        ReplacementNotes.of(root) shouldBe emptyMap()
    }

    should("fail when a member of a permissible group has no position") {
        val root = rootOf(
            usages = listOf(at(1, 5), usage(2, Field.Missing)),
            groups = listOf(group(1, listOf(1), listOf(2))),
        )

        shouldThrow<SpecificationDataException> { ReplacementNotes.of(root) }.message shouldContain "member 2"
    }

    should("fail when the position of a member of a permissible group is not loaded") {
        val root = rootOf(
            usages = listOf(at(1, 5), usage(2, Field.NotLoaded)),
            groups = listOf(group(1, listOf(1), listOf(2))),
        )

        shouldThrow<SpecificationDataException> { ReplacementNotes.of(root) }
    }

    should("fail when a permissible group has no basic variant") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7)),
            groups = listOf(
                ReplacementGroup(
                    id = 1,
                    name = null,
                    type = ReplacementType.Permissible,
                    variants = listOf(
                        ReplacementVariant(id = 11, name = null, basic = false, linkIds = listOf(2), inCurrentConfiguration = true),
                    ),
                    inCurrentConfiguration = true,
                ),
            ),
        )

        shouldThrow<SpecificationDataException> { ReplacementNotes.of(root) }.message shouldContain "basic variant"
    }

    should("ignore a member of a permissible group that is not a composition of the root") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(3, 7)),
            groups = listOf(group(1, listOf(1), listOf(2, 3))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7")),
            3 to ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5")),
        )
    }

    should("skip a permissible group whose basic variant is not a composition of the root") {
        val root = rootOf(
            usages = listOf(at(3, 7)),
            groups = listOf(group(1, listOf(1), listOf(3))),
        )

        ReplacementNotes.of(root) shouldBe emptyMap()
    }

    should("skip a permissible group whose non-basic variants are not compositions of the root") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7)),
            groups = listOf(group(1, listOf(1), listOf(3, 4))),
        )

        ReplacementNotes.of(root) shouldBe emptyMap()
    }

    should("skip only the outside non-basic variant and keep the other variant of the group") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7), at(3, 8)),
            groups = listOf(group(1, listOf(1), listOf(2), listOf(9))),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7")),
            2 to ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5")),
        )
    }

    should("fail when a raw permissible group has two basic variants") {
        val root = rootOf(
            usages = listOf(at(1, 5), at(2, 7)),
            groups = listOf(
                ReplacementGroup(
                    id = 1,
                    name = null,
                    type = ReplacementType.Permissible,
                    variants = listOf(
                        ReplacementVariant(id = 11, name = null, basic = true, linkIds = listOf(1), inCurrentConfiguration = true),
                        ReplacementVariant(id = 12, name = null, basic = true, linkIds = listOf(2), inCurrentConfiguration = true),
                    ),
                    inCurrentConfiguration = true,
                ),
            ),
        )

        shouldThrow<SpecificationDataException> { ReplacementNotes.of(root) }.message shouldContain "basic variant"
    }

    should("give a production member the application method of its composition link") {
        val root = rootOf(
            usages = listOf(withMethod(1, 5, AttributeValue.Text("Окраска"))),
            groups = listOf(group(1, listOf(1), type = ReplacementType.Production)),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = emptyList(), applicationMethod = "Окраска"),
        )
    }

    should("skip a production member without the application method") {
        val root = rootOf(
            usages = listOf(at(1, 5), withMethod(2, 7, AttributeValue.Text("  ")), withMethod(3, 8, AttributeValue.Empty)),
            groups = listOf(group(1, listOf(1, 2, 3), type = ReplacementType.Production)),
        )

        ReplacementNotes.of(root) shouldBe emptyMap()
    }

    should("ignore a production member that is not a composition of the root") {
        val root = rootOf(
            usages = listOf(withMethod(1, 5, AttributeValue.Text("Окраска"))),
            groups = listOf(group(1, listOf(1, 2), type = ReplacementType.Production)),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = emptyList(), applicationMethod = "Окраска"),
        )
    }

    should("not fail for a production group with two basic variants") {
        val root = rootOf(
            usages = listOf(withMethod(1, 5, AttributeValue.Text("Сварка")), withMethod(2, 7, AttributeValue.Text("Пайка"))),
            groups = listOf(
                ReplacementGroup(
                    id = 1,
                    name = null,
                    type = ReplacementType.Production,
                    variants = listOf(
                        ReplacementVariant(id = 11, name = null, basic = true, linkIds = listOf(1), inCurrentConfiguration = true),
                        ReplacementVariant(id = 12, name = null, basic = true, linkIds = listOf(2), inCurrentConfiguration = true),
                    ),
                    inCurrentConfiguration = true,
                ),
            ),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = emptyList(), applicationMethod = "Сварка"),
            2 to ReplacementNote(isNonBasic = false, texts = emptyList(), applicationMethod = "Пайка"),
        )
    }

    should("keep the replacement texts and the application method of a member in production and permissible groups") {
        // поз. 5 — базовый в допускаемой замене (поз. 7) и член производственной группы со способом применения
        val root = rootOf(
            usages = listOf(withMethod(1, 5, AttributeValue.Text("Окраска")), at(2, 7)),
            groups = listOf(
                group(1, listOf(1), listOf(2)),
                group(2, listOf(1), type = ReplacementType.Production),
            ),
        )

        ReplacementNotes.of(root) shouldBe mapOf(
            1 to ReplacementNote(isNonBasic = false, texts = listOf("допуск. замена на поз. 7"), applicationMethod = "Окраска"),
            2 to ReplacementNote(isNonBasic = true, texts = listOf("примен. взамен поз. 5")),
        )
    }
})
