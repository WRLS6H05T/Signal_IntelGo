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

rootProject.name = "Signal_IntelGo"
include(":app")
include(":shared")
// The desk host: the same protocol and sweep code as the phone, run from a PC with the boards
// plugged into it, because a cable and a mains supply belong on a desk rather than a windscreen.
include(":desktop")
