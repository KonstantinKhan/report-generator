package dev.reportgenerator.domain

sealed interface Components {
    data object NotLoaded : Components
    data class Loaded(
        val parts: List<Component<Part>>,
        val assemblies: List<Component<Assembly>>,
        val standardProducts: List<Component<StandardProduct>>,
        val otherProducts: List<Component<OtherProduct>>,
    ) : Components {
        val all: List<Component<Item>>
            get() = (parts + assemblies + standardProducts + otherProducts).sortedWith(componentComparator)
    }
}

private val componentComparator = Comparator<Component<Item>> { a, b ->
    val groupA = getComponentGroup(a)
    val groupB = getComponentGroup(b)

    if (groupA != groupB) {
        groupA.compareTo(groupB)
    } else {
        when (groupA) {
            0 -> {
                // Group 0: by position number (ascending)
                val posA = (a.position as Field.Value<Int>).value
                val posB = (b.position as Field.Value<Int>).value
                posA.compareTo(posB)
            }
            1 -> {
                // Group 1: by designation, case-insensitive
                val designA = (a.item.designation as Designation.Value).text
                val designB = (b.item.designation as Designation.Value).text
                val cmp = String.CASE_INSENSITIVE_ORDER.compare(designA, designB)
                if (cmp != 0) cmp
                else {
                    // Tiebreaker: name
                    val nameA = a.item.name.orNull().orEmpty()
                    val nameB = b.item.name.orNull().orEmpty()
                    val nameCmp = String.CASE_INSENSITIVE_ORDER.compare(nameA, nameB)
                    if (nameCmp != 0) nameCmp else a.item.id.compareTo(b.item.id)
                }
            }
            else -> {
                // Group 2: by name, case-insensitive
                val nameA = a.item.name.orNull().orEmpty()
                val nameB = b.item.name.orNull().orEmpty()
                val nameCmp = String.CASE_INSENSITIVE_ORDER.compare(nameA, nameB)
                if (nameCmp != 0) nameCmp
                else {
                    // Tiebreaker: designation
                    val designA = a.item.designation.orNull().orEmpty()
                    val designB = b.item.designation.orNull().orEmpty()
                    val designCmp = String.CASE_INSENSITIVE_ORDER.compare(designA, designB)
                    if (designCmp != 0) designCmp else a.item.id.compareTo(b.item.id)
                }
            }
        }
    }
}

private fun getComponentGroup(component: Component<Item>): Int {
    return when {
        component.position is Field.Value -> 0
        component.item.designation is Designation.Value &&
            (component.item.designation as Designation.Value).text.isNotBlank() -> 1
        else -> 2
    }
}
