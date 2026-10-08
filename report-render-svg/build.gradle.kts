plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportLayoutIr)

    testImplementation(projects.reportIr)
    testImplementation(projects.reportLayout)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
