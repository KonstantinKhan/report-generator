dependencies {
    api(project(":report-data"))
    api(project(":report-ir"))
    implementation(project(":report-geometry"))

    testImplementation(project(":report-layout"))
    testImplementation(project(":report-render-svg"))
    testImplementation(project(":report-render-pdf"))
    testImplementation(libs.pdfbox)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

// StaticBlocksGoldenTest lays out several multi-page documents; PdfBoxTextMeasurer re-parses the
// TTF on every measure() call (see FontRegistry), which exceeds the default test heap.
tasks.test {
    maxHeapSize = "4g"
}
