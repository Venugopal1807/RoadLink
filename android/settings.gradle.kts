pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RoadLink"

// The RoadLink product.
include(":app")

// The S0-S5 BLE hardware harness. Kept intact and dependency-free until
// physical-device validation has actually run - docs/s0-s5-runbook.md is
// written against this exact APK.
include(":spike-ble")
