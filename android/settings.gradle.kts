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

rootProject.name = "MusicStream"

include(":app")

// Shared infrastructure: no feature logic, never depends on a feature.
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:ui")
include(":core:playback")

// Features: each owns its domain, data and presentation layers.
include(":feature:session")
include(":feature:library")
include(":feature:playlist")
include(":feature:podcast")
include(":feature:radio")
include(":feature:player")
include(":feature:discover")
