rootProject.name = "report-engine"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "report-geometry",
    "report-ir",
    "report-layout-ir",
    "report-layout",
    "report-template",
    "report-render-svg",
    "report-render-pdf",
    "report-api",
    "report-data",
    "reports:specification",
    "report-cli",
    "report-loodsman",
    "report-server",
)
