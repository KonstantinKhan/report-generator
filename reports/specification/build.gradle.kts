plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportDomain)
    api(projects.reportIr)
    implementation(projects.reportGeometry)

    testImplementation(projects.reportLayout)
    testImplementation(projects.reportRenderSvg)
    testImplementation(projects.reportRenderPdf)
    testImplementation(libs.pdfbox)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// StaticBlocksGoldenTest lays out several multi-page documents; PdfBoxTextMeasurer re-parses the
// TTF on every measure() call (see FontRegistry), which exceeds the default test heap.
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}
