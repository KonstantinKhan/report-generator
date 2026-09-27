rootProject.name = "report-engine"

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
    "report-render-svg",
    "report-render-pdf",
    "report-api",
    "report-data",
    "reports:specification",
    "report-cli",
    "report-loodsman",
    "report-server",
)
