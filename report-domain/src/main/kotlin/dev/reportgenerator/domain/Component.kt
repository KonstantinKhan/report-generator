package dev.reportgenerator.domain

data class Component<out T : Item>(
    val item: T,
    val quantity: Quantity,
    val unit: Field<String>,
    val position: Field<Int>
)
