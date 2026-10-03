package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.dataContext

// Adapter SpecificationData -> DataContext (schema + values) for the template binds of the specification
// sheet. Extend by adding fields to `doc { }`: the schema is declared by the same builder that supplies the values.
fun SpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
    }
}
