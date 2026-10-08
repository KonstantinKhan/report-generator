package dev.reportgenerator.template

// Layout-time use of a `bind: "${root.a.b}"` expression.
object Binding {
    // "${doc.designation}" -> "doc.designation"
    fun path(bind: String): String = bind.removePrefix("\${").removeSuffix("}")

    // Text for a bound cell / text block. Missing value: "" when `optional`, otherwise an error
    // (IllegalStateException; the contract check guarantees the path exists in the schema, but only the
    // data knows whether a value is there). A non-scalar value or a format not fitting the type is an error too.
    fun render(bind: String, format: FormatSpec?, optional: Boolean, data: DataContext): String {
        val path = path(bind)
        val value = data.get(path)
            ?: return if (optional) "" else error("no value for bind '$bind' (mark the cell 'optional: true' to render it empty)")
        check(value.type.isScalar) { "bind '$bind' is a ${value.type.typeName}, not a scalar value" }
        return ValueFormatter.render(value, format)
    }
}
