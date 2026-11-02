package dev.reportgenerator.domain

@JvmInline
value class PdmNodeId(private val value: Int) {
    fun asInt() = value
}
