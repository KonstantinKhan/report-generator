package dev.reportgenerator.loodsman.mapping

class Registry<T : Any>(private val kind: String, private val entries: Map<String, T>) {

    fun resolve(id: String): T {
        return entries[id] ?: throw IllegalArgumentException(
            "Unknown $kind '$id' in mapping config. Known: ${entries.keys.joinToString(", ")}"
        )
    }

    fun idOf(value: T): String? = entries.entries.firstOrNull { it.value == value }?.key

    val values: Collection<T> get() = entries.values
}
