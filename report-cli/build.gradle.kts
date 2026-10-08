plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.reportgenerator.cli.MainKt")
}

dependencies {
    implementation(projects.reports.specification)
    implementation(projects.reportLayout)
    implementation(projects.reportTemplate)
    implementation(projects.reportRenderSvg)
    implementation(projects.reportRenderPdf)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

// ./gradlew :report-cli:runTemplate -Pargs="my.yaml out --pages 2 --data data.yaml"  (no args = bundled sheet-frame.yaml -> output/)
tasks.register<JavaExec>("runTemplate") {
    group = "application"
    description = "Render a YAML template (arg 0) to SVG + PDF in a directory (arg 1)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.reportgenerator.cli.TemplateMainKt")
    workingDir = projectDir
    val extra = providers.gradleProperty("args")
    argumentProviders.add(CommandLineArgumentProvider { extra.orNull?.split(" ")?.filter { it.isNotBlank() } ?: emptyList() })
}

// The flow table test lays out several pages; PdfBoxTextMeasurer re-parses the TTF on every measure() call,
// which exceeds the default test heap (same as reports:specification).
tasks.test {
    maxHeapSize = "4g"
}
