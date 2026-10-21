package khan366kos.loodsman.api.dsl

import dev.reportgenerator.domain.PdmNode

suspend fun pdmNode(block: PdmNodeBuilder.() -> Unit): PdmNode =
    PdmNodeBuilder().apply(block).build()