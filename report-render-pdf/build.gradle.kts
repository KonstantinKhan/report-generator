plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportLayoutIr)
    implementation(projects.reportLayout)
    implementation(libs.pdfbox)

    testImplementation(projects.reportIr)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
