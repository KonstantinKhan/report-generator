package dev.reportgenerator.template

internal val SELF_ATTACH = AttachSpec.single(self = "topLeft", to = "self.topLeft")

internal sealed interface Topo {
    data class Order(val blocks: List<BlockSpec>) : Topo
    data class Cycle(val ids: List<String>) : Topo
}

internal fun attachOf(block: BlockSpec, default: AttachSpec): AttachSpec = block.attach ?: default

// Attach edges point block -> target (one per axis, so up to two). Roots (sheet/self) are not nodes. Declaration order is kept
// wherever dependencies allow, so output is deterministic.
internal fun topoSort(blocks: List<BlockSpec>, default: AttachSpec): Topo {
    val byId = blocks.associateBy { it.id }
    val state = HashMap<String, Int>() // 1 = on stack, 2 = done
    val stack = ArrayList<String>()
    val order = ArrayList<BlockSpec>()

    fun visit(block: BlockSpec): List<String>? {
        state[block.id] = 1
        stack += block.id
        val targets = attachOf(block, default).axes
            .mapNotNull { axis -> parseAnchorRef(axis.to)?.blockId?.let { byId[it] } }
            .distinctBy { it.id }
        for (target in targets) {
            when (state[target.id]) {
                1 -> return stack.subList(stack.indexOf(target.id), stack.size) + target.id
                null -> visit(target)?.let { return it }
            }
        }
        stack.removeAt(stack.lastIndex)
        state[block.id] = 2
        order += block
        return null
    }

    for (b in blocks) if (state[b.id] == null) visit(b)?.let { return Topo.Cycle(it) }
    return Topo.Order(order)
}
