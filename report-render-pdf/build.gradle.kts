dependencies {
    api(project(":report-layout-ir"))
    implementation(project(":report-layout"))
    implementation(libs.pdfbox)

    testImplementation(project(":report-ir"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
