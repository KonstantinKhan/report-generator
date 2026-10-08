package dev.reportgenerator.template

// `path` is the YAML location (e.g. `blocks[2].attach.to`), so the message points at the exact field.
data class TemplateError(val path: String, val message: String) {
    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

class TemplateException(val errors: List<TemplateError>) : RuntimeException(errors.joinToString("\n")) {
    constructor(path: String, message: String) : this(listOf(TemplateError(path, message)))
}
