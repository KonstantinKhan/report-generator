package dev.reportgenerator.reports.specification

// Invalid or incomplete product data that cannot be turned into a specification.
// Not an IllegalStateException on purpose: it is a data problem, not a bug.
class SpecificationDataException(message: String) : RuntimeException(message)
