dependencies {
    api(project(":report-geometry"))
    implementation(libs.kaml)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
