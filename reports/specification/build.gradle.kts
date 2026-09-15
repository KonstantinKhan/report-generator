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
