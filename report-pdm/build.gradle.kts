plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportDomain)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
