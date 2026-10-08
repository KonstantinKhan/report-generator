plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportGeometry)
    api(projects.reportTemplate)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
