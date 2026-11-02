package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.ReplacementType
import dev.reportgenerator.domain.ReplacementVariant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.V3_GetInfoAboutVersionMode20OutputDto

// Строки объекта 4757 (ответ get-info-about-version-mode-20): группа, тип группы, вариант, базовость, связь.
private fun row(
    group: Int,
    groupType: Int,
    variant: Int,
    basic: Int,
    idLink: Int,
    groupName: String? = null,
    variantName: String? = null,
    groupInCurrentConf: Int = 1,
    variantInCurrentConf: Int = 1,
) = V3_GetInfoAboutVersionMode20OutputDto(
    idGroupChange = group,
    groupChangeName = groupName,
    groupChangeType = groupType,
    groupInCurrentConf = groupInCurrentConf,
    idVariant = variant,
    variantName = variantName,
    basic = basic,
    variantInCurrentConf = variantInCurrentConf,
    idLink = idLink,
    idParent = 4757,
    linkType = "Состоит из ...",
    idVersion = 0,
    idType = 0,
    idState = 0,
    idLock = 0,
    accessLevel = 0,
    label = 0,
)

private val object4757Rows = listOf(
    row(240, 2, 271, 1, 10045, groupName = "Замена 240"),
    row(240, 2, 272, 0, 10046, groupName = "Замена 240"),
    row(240, 2, 272, 0, 10037, groupName = "Замена 240"),
    row(241, 1, 273, 1, 10035, groupName = "Замена 241"),
    row(241, 1, 274, 0, 10036, groupName = "Замена 241"),
    row(239, 2, 269, 1, 10028, groupName = "Замена 239"),
    row(239, 2, 270, 0, 10034, groupName = "Замена 239"),
)

class ReplacementLoaderTest : ShouldSpec({

    should("group rows of object 4757 by group and variant keeping response order") {
        ReplacementLoader.groupsOf(object4757Rows) shouldBe listOf(
            ReplacementGroup(
                id = 240,
                name = "Замена 240",
                type = ReplacementType.Permissible,
                variants = listOf(
                    ReplacementVariant(271, null, basic = true, linkIds = listOf(10045), inCurrentConfiguration = true),
                    ReplacementVariant(272, null, basic = false, linkIds = listOf(10046, 10037), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
            ReplacementGroup(
                id = 241,
                name = "Замена 241",
                type = ReplacementType.Production,
                variants = listOf(
                    ReplacementVariant(273, null, basic = true, linkIds = listOf(10035), inCurrentConfiguration = true),
                    ReplacementVariant(274, null, basic = false, linkIds = listOf(10036), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
            ReplacementGroup(
                id = 239,
                name = "Замена 239",
                type = ReplacementType.Permissible,
                variants = listOf(
                    ReplacementVariant(269, null, basic = true, linkIds = listOf(10028), inCurrentConfiguration = true),
                    ReplacementVariant(270, null, basic = false, linkIds = listOf(10034), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
        )
    }

    should("map group change type 0 to consumer and 1 to production") {
        ReplacementLoader.groupsOf(listOf(row(1, 0, 10, 1, 5), row(2, 1, 20, 1, 6))).map { it.type } shouldBe
            listOf(ReplacementType.Consumer, ReplacementType.Production)
    }

    should("throw LoodsmanApiException on unknown group change type") {
        shouldThrow<LoodsmanApiException> {
            ReplacementLoader.groupsOf(listOf(row(7, 5, 10, 1, 5)))
        }
    }

    should("keep the group and variant flags of the current configuration as they come") {
        val groups = ReplacementLoader.groupsOf(
            listOf(
                row(1, 2, 10, 1, 5, groupInCurrentConf = 0, variantInCurrentConf = 1),
                row(1, 2, 11, 0, 6, groupInCurrentConf = 0, variantInCurrentConf = 0),
                row(2, 2, 20, 1, 7, groupInCurrentConf = 1, variantInCurrentConf = 0),
            ),
        )

        groups.map { it.inCurrentConfiguration } shouldBe listOf(false, true)
        groups[0].variants.map { it.inCurrentConfiguration } shouldBe listOf(true, false)
        groups[1].variants.map { it.inCurrentConfiguration } shouldBe listOf(false)
    }

    should("keep the members of every group in response order") {
        ReplacementLoader.groupsOf(object4757Rows).flatMap { group -> group.variants.flatMap { it.linkIds } } shouldBe
            listOf(10045, 10046, 10037, 10035, 10036, 10028, 10034)
    }
})
