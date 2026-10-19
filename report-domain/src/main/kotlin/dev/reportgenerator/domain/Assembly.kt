package dev.reportgenerator.domain

data class Assembly(
    override val id: Int,
    override val designation: Designation,
    override val name: Name,
    override val state: String,
    override val version: String,
    val components: Components
) : Item {
    val isLoaded: Boolean get() = components is Components.Loaded

    val parts: List<Component<Part>> get() = loaded().parts
    val assemblies: List<Component<Assembly>> get() = loaded().assemblies
    val standardProducts: List<Component<StandardProduct>> get() = loaded().standardProducts
    val otherProducts: List<Component<OtherProduct>> get() = loaded().otherProducts
    val all: List<Component<Item>> get() = loaded().all

    private fun loaded(): Components.Loaded = when (components) {
        is Components.Loaded -> components
        Components.NotLoaded -> throw IllegalStateException(
            "Components of assembly id=$id are not loaded: the tree was built with a limited number of levels"
        )
    }
}
