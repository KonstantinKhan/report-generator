package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.ReplacementType

// Replacement notes of the root's items. Only the permissible groups (ReplacementType.Permissible) take part; the groups
// of the other types are ignored and their members stay ordinary rows. A member is matched to the root's composition
// usage by linkId, its position comes from that usage. A member that is not a composition of the root is ignored;
// a permissible group left without a basic member or without a non-basic member is skipped (its members stay ordinary rows).
//
// Result: linkId -> note. `isNonBasic` = the member is in a non-basic variant of a permissible group (its quantity is not
// shown). `texts` = the replacement texts of the member: first the basic text ("допуск. замена на ..." of the groups where
// the member is basic), then the non-basic text ("примен. ..." of the groups where the member is non-basic). The text of
// the basic member starts with a lowercase letter here, the item builder capitalizes it when it is the first note.
internal data class ReplacementNote(val isNonBasic: Boolean, val texts: List<String>)

internal object ReplacementNotes {
    internal const val BASIC_TEXT_START = "допуск. замена"
    private const val BASIC_PREFIX = "$BASIC_TEXT_START на "
    private const val NON_BASIC_PREFIX = "примен. "
    private const val OR_SEPARATOR = " или "

    fun of(root: PdmNode): Map<Int, ReplacementNote> {
        val compositionByLinkId = root.usages.filterIsInstance<CompositionUsage>()
            .mapNotNull { usage -> usage.linkId?.let { it to usage } }
            .toMap()

        // basic member -> the fragments of the non-basic variants of every group where it is basic
        val fragmentsOfBasic = linkedMapOf<Int, MutableList<String>>()
        // non-basic member -> the "с поз. ... взамен поз. ..." part of every group where it is non-basic
        val replacedBy = linkedMapOf<Int, MutableList<String>>()
        val nonBasic = mutableSetOf<Int>()

        for (group in root.replacements) {
            if (group.type != ReplacementType.Permissible) continue
            if (group.variants.count { it.basic } != 1) {
                throw SpecificationDataException("Replacement group ${group.id}: needs exactly one basic variant")
            }

            // only the members that are compositions of the root take part; the others are not in the sheet
            val basicLinkIds = group.variants.single { it.basic }.linkIds.filter { it in compositionByLinkId }
            val otherLinkIds = group.variants.filterNot { it.basic }
                .map { variant -> variant.linkIds.filter { it in compositionByLinkId } }
                .filter { it.isNotEmpty() }
            if (basicLinkIds.isEmpty() || otherLinkIds.isEmpty()) continue

            // every kept member needs a position, also in a group that gives no text
            val basicPositions = group.positionsOf(basicLinkIds, compositionByLinkId)
            val otherPositions = otherLinkIds.map { group.positionsOf(it, compositionByLinkId) }

            val groupFragments = otherPositions.map { fragmentOf(it) }
            for ((linkId, _) in basicPositions) {
                fragmentsOfBasic.getOrPut(linkId) { mutableListOf() }.addAll(groupFragments)
            }

            val basicText = basicPositions.joinToString(", ") { (_, position) -> position.toString() }
            for (variant in otherPositions) {
                for ((linkId, _) in variant) {
                    nonBasic += linkId
                    val companions = variant.filter { it.first != linkId }
                    val companionsText = if (companions.isEmpty()) "" else "с поз. ${positionsText(companions)} "
                    replacedBy.getOrPut(linkId) { mutableListOf() }.add("${companionsText}взамен поз. $basicText")
                }
            }
        }

        val linkIds = fragmentsOfBasic.keys + replacedBy.keys
        return linkIds.associateWith { linkId ->
            val texts = listOfNotNull(
                fragmentsOfBasic[linkId]?.let { BASIC_PREFIX + it.joinToString(OR_SEPARATOR) },
                replacedBy[linkId]?.let { NON_BASIC_PREFIX + it.joinToString(OR_SEPARATOR) },
            )
            ReplacementNote(isNonBasic = linkId in nonBasic, texts = texts)
        }
    }

    // Members of a variant (compositions of the root only) with their positions, by position ascending.
    private fun ReplacementGroup.positionsOf(
        linkIds: List<Int>,
        compositionByLinkId: Map<Int, CompositionUsage>,
    ): List<Pair<Int, Int>> = linkIds.map { linkId ->
        val usage = compositionByLinkId.getValue(linkId)
        val position = usage.positionOf()
            ?: throw SpecificationDataException("Replacement group $id: position of member $linkId is missing")
        linkId to position
    }.sortedBy { (_, position) -> position }

    // "поз. <min>" and, for several members, " совместно с поз. <the others>": the text of one non-basic variant.
    private fun fragmentOf(members: List<Pair<Int, Int>>): String {
        val first = members.first()
        val rest = members.drop(1)
        return if (rest.isEmpty()) "поз. ${first.second}"
        else "поз. ${first.second} совместно с поз. ${positionsText(rest)}"
    }

    private fun positionsText(members: List<Pair<Int, Int>>): String =
        members.joinToString(", ") { (_, position) -> position.toString() }
}
