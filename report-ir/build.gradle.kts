dependencies {
    api(project(":report-geometry"))
    api(project(":report-template"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
