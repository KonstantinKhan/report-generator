dependencies {
    api(project(":report-layout-ir"))

    testImplementation(project(":report-ir"))
    testImplementation(project(":report-layout"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
