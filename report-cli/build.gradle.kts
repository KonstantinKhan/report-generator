plugins {
    application
}

application {
    mainClass.set("dev.reportgenerator.cli.MainKt")
}

dependencies {
    implementation(project(":reports:specification"))
    implementation(project(":report-layout"))
    implementation(project(":report-render-svg"))
    implementation(project(":report-render-pdf"))
}
