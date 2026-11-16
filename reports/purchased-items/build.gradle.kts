plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportDomain)
    api(projects.reportIr)
    implementation(projects.reportGeometry)
    implementation(projects.reportTemplate)

    testImplementation(projects.reportLayout)
    testImplementation(projects.reportRenderPdf)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

