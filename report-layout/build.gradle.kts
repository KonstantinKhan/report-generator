dependencies {
    api(project(":report-ir"))
    api(project(":report-layout-ir"))
    implementation(libs.pdfbox)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
