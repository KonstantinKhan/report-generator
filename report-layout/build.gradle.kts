plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportIr)
    api(projects.reportLayoutIr)
    implementation(libs.pdfbox)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
